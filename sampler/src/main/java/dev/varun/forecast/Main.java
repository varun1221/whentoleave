package dev.varun.forecast;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import dev.varun.forecast.DepartureSchedule.Slot;
import dev.varun.forecast.model.ForecastsFile;
import dev.varun.forecast.model.Route;
import dev.varun.forecast.model.RouteResult;
import dev.varun.forecast.model.Sample;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * The whole of Phase 1's runtime: read config, call HTTP, write files, exit.
 *
 * <pre>
 *   sweep      (default) full 5 x 7 x 13 sweep, appends JSONL, rebuilds forecasts.json
 *   aggregate            rebuild forecasts.json from existing JSONL, zero API calls
 *   probe                one call, one route — proves the API works (build order step 1)
 *   spread               one route across the day — proves the premise (step 2)
 * </pre>
 */
public final class Main {

    private static final Path ROUTES_CONFIG = Path.of("config/routes.json");
    private static final Path SAMPLES_DIR = Path.of("data/samples");
    private static final Path FORECASTS_OUT = Path.of("web/public/data/forecasts.json");

    /**
     * In-process ceiling, independent of the GCP daily quota. The cron is weekly, so the
     * daily quota alone does not bound the monthly total; the two together do.
     */
    private static final int DEFAULT_MAX_CALLS = 500;

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "sweep";
        int exitCode = switch (mode) {
            case "sweep" -> sweep();
            case "aggregate" -> aggregate();
            case "probe" -> probe();
            case "spread" -> spread();
            default -> {
                System.err.println("Unknown mode: " + mode
                        + " (expected sweep, aggregate, probe or spread)");
                yield 2;
            }
        };
        System.exit(exitCode);
    }

    // ---------------------------------------------------------------- sweep

    private static int sweep() throws Exception {
        List<Route> routes = readRoutes();
        List<Route> usable = routes.stream().filter(Route::isConfigured).toList();
        if (usable.isEmpty()) {
            System.err.println("No configured routes — fill in the coordinates in "
                    + ROUTES_CONFIG + " before sweeping.");
            return 2;
        }
        if (usable.size() < routes.size()) {
            System.out.println("Skipping " + (routes.size() - usable.size())
                    + " route(s) with unusable coordinates.");
        }

        List<Slot> slots = DepartureSchedule.nextSevenDays();
        int planned = usable.size() * slots.size();
        int maxCalls = maxCalls();
        if (planned > maxCalls) {
            System.err.println("ABORT: planned " + planned + " calls exceeds MAX_CALLS="
                    + maxCalls + ". Reduce routes or raise the ceiling deliberately.");
            return 2;
        }

        String key = apiKey();
        if (key == null) {
            return 2;
        }
        RoutingClient client = new RoutingClient(key);
        SampleStore store = new SampleStore(SAMPLES_DIR);
        int appended = 0;
        int failures = 0;
        int noRoute = 0;

        for (Route route : usable) {
            for (Slot slot : slots) {
                try {
                    Optional<RouteResult> result =
                            client.compute(route, slot.departureTimeUtc());
                    if (result.isEmpty()) {
                        noRoute++;
                        continue;
                    }
                    store.append(route.id(), new Sample(
                            slot.targetLocal(),
                            slot.dayOfWeek().name(),
                            slot.slotHour(),
                            result.get().durationSeconds(),
                            result.get().distanceMeters(),
                            nowIso()));
                    appended++;
                } catch (IOException e) {
                    // One bad coordinate pair must not cost the sweep.
                    failures++;
                    System.err.println("FAIL " + route.id() + " @ " + slot.targetLocal()
                            + ": " + e.getMessage());
                }
            }
        }

        ForecastsFile forecasts = new Aggregator().build(routes, new SampleStore(SAMPLES_DIR));
        new Aggregator().writeTo(FORECASTS_OUT, forecasts);

        System.out.printf(
                "sweep complete: routes=%d slots=%d calls=%d appended=%d noRoute=%d "
                        + "failures=%d sweepsSampled=%d totalSamples=%d%n",
                usable.size(), slots.size(), client.callsMade(), appended, noRoute,
                failures, forecasts.sweepsSampled(), forecasts.totalSamples());

        // Non-zero only when nothing at all got through. A partial sweep still improves
        // the medians and still deploys.
        return appended == 0 ? 1 : 0;
    }

    // ------------------------------------------------------------ aggregate

    private static int aggregate() throws Exception {
        Aggregator aggregator = new Aggregator();
        ForecastsFile forecasts =
                aggregator.build(readRoutes(), new SampleStore(SAMPLES_DIR));
        aggregator.writeTo(FORECASTS_OUT, forecasts);
        System.out.printf("aggregate complete: routes=%d sweepsSampled=%d totalSamples=%d "
                        + "-> %s%n",
                forecasts.routes().size(), forecasts.sweepsSampled(),
                forecasts.totalSamples(), FORECASTS_OUT);
        return 0;
    }

    // ---------------------------------------------------------------- probe

    /** Build order step 1: one route, one future departureTime, print the duration. */
    private static int probe() throws Exception {
        Route route = firstConfiguredRoute();
        if (route == null) {
            return 2;
        }
        Slot slot = DepartureSchedule.nextSevenDays().get(2); // tomorrow, 08:00 local
        String key = apiKey();
        if (key == null) {
            return 2;
        }
        RoutingClient client = new RoutingClient(key);
        Optional<RouteResult> result = client.compute(route, slot.departureTimeUtc());
        if (result.isEmpty()) {
            System.out.println("No route found for " + route.id());
            return 1;
        }
        System.out.printf("%s  departing %s (%s)  ->  %s  (%.1f km)%n",
                route.name(), slot.targetLocal(), slot.departureTimeUtc(),
                minutes(result.get().durationSeconds()),
                result.get().distanceMeters() == null
                        ? 0.0 : result.get().distanceMeters() / 1000.0);
        return 0;
    }

    // --------------------------------------------------------------- spread

    /**
     * Build order step 2: one route across 06:00-18:00. If best and worst hour are close
     * together, the corridor is flat and the whole app is pointless — that is what this
     * mode exists to find out, before anything else gets built on top of it.
     */
    private static int spread() throws Exception {
        Route route = firstConfiguredRoute();
        if (route == null) {
            return 2;
        }
        // A weekend day is flat by definition. Testing the premise on a Sunday would
        // report no spread on a corridor that has plenty on a Tuesday.
        List<Slot> day = firstWeekday(DepartureSchedule.nextSevenDays());
        String key = apiKey();
        if (key == null) {
            return 2;
        }
        RoutingClient client = new RoutingClient(key);

        System.out.println(route.name() + " on " + day.get(0).dayOfWeek());
        long best = Long.MAX_VALUE;
        long worst = Long.MIN_VALUE;
        int failures = 0;

        for (Slot slot : day) {
            try {
                Optional<RouteResult> result =
                        client.compute(route, slot.departureTimeUtc());
                if (result.isEmpty()) {
                    System.out.printf("  %02d:00   no route%n", slot.slotHour());
                    continue;
                }
                long seconds = result.get().durationSeconds();
                best = Math.min(best, seconds);
                worst = Math.max(worst, seconds);
                System.out.printf("  %02d:00   %s%n", slot.slotHour(), minutes(seconds));
            } catch (IOException e) {
                failures++;
                System.err.printf("  %02d:00   FAILED: %s%n", slot.slotHour(),
                        e.getMessage());
            }
        }
        if (best == Long.MAX_VALUE) {
            System.err.println("Every call failed.");
            return 1;
        }
        System.out.printf("%nbest %s / worst %s — spread %s (%.0f%%), failures=%d%n",
                minutes(best), minutes(worst), minutes(worst - best),
                100.0 * (worst - best) / best, failures);
        System.out.println(
                "A spread under roughly 25% means this corridor will not carry the demo.");
        return 0;
    }

    // ----------------------------------------------------------------- util

    /** The 13 slots of the first Monday-to-Friday date in the schedule. */
    static List<Slot> firstWeekday(List<Slot> slots) {
        java.time.LocalDate weekday = slots.stream()
                .map(s -> s.local().toLocalDate())
                .filter(d -> d.getDayOfWeek().getValue() <= 5)
                .findFirst()
                .orElseThrow();
        return slots.stream()
                .filter(s -> s.local().toLocalDate().equals(weekday))
                .toList();
    }

    private static Route firstConfiguredRoute() throws IOException {
        Optional<Route> route = readRoutes().stream().filter(Route::isConfigured).findFirst();
        if (route.isEmpty()) {
            System.err.println("No configured routes — fill in the coordinates in "
                    + ROUTES_CONFIG + " first.");
            return null;
        }
        return route.get();
    }

    private static List<Route> readRoutes() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        CollectionType type = mapper.getTypeFactory()
                .constructCollectionType(List.class, Route.class);
        return mapper.readValue(Files.readString(ROUTES_CONFIG), type);
    }

    /** A missing key is a setup mistake, not a crash. Say what to do about it. */
    private static String apiKey() {
        String key = System.getenv("TOMTOM_API_KEY");
        if (key == null || key.isBlank()) {
            System.err.println("""
                    TOMTOM_API_KEY is not set.

                      Get a free key at https://developer.tomtom.com (no card required),
                      then:  cp .env.example .env  and paste it in, followed by
                             set -a; source .env; set +a
                    """);
            return null;
        }
        return key;
    }

    private static int maxCalls() {
        String override = System.getenv("MAX_CALLS");
        if (override == null || override.isBlank()) {
            return DEFAULT_MAX_CALLS;
        }
        return Integer.parseInt(override.trim());
    }

    private static String minutes(long seconds) {
        return String.format("%d min %02d s", seconds / 60, seconds % 60);
    }

    private static String nowIso() {
        return DateTimeFormatter.ISO_INSTANT.format(
                Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }

    private Main() {}
}
