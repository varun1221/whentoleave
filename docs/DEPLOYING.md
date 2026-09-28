# Deploying

Everything here is one-time setup. After it, the whole system runs unattended: the
weekly sweep refreshes the data, the site and the API redeploy themselves, and nothing
needs a human.

- [The site](#the-site): Cloudflare Pages
- [The service](#the-service): Cloud Run behind Cloudflare
- [Loading each sweep into Neon](#loading-each-sweep-into-neon)

## The site

The static site is Cloudflare Pages building `web/`:

- Root directory `web`, build command `npm run build`, output directory `dist`.
- `NODE_VERSION` pinned to 22, so the Pages build matches the one CI tests.
- `whentoleave.me` is registered at Namecheap, with its nameservers moved to Cloudflare,
  and both the apex and `www` are attached as Pages custom domains. The zone has to live
  in Cloudflare, because the service's Transform Rule below needs it; a domain merely
  pointed at Pages would not have one.
- The weekly `sample` workflow commits new data to `main`, and Pages redeploys on its own.

## The service

`deploy-service.yml` runs on every push to `main` that touches `service/**`: build the
image, push it to Artifact Registry, `gcloud run deploy` with the §7 flags. It holds **no
long-lived credential** — GitHub authenticates as this repository via Workload Identity
Federation, and everything secret lives in GCP Secret Manager, so nothing sensitive
passes through the deploy at all. The only database credential Actions holds is the
weekly import's, a role that can only add public data (see
[Loading each sweep into Neon](#loading-each-sweep-into-neon)).

That means some one-time setup outside the repo. In GCP, once:

```bash
PROJECT=your-project-id
REGION=us-central1              # or us-east1 / us-west1 — the free tier exists nowhere else

gcloud artifacts repositories create forecast \
  --repository-format=docker --location="$REGION"

# The secrets the service reads. Generate the origin secret and the IP hash key here;
# they exist nowhere else and nothing needs to know their values.
printf '%s' "$(openssl rand -hex 32)" | gcloud secrets create forecast-origin-secret  --data-file=-
printf '%s' "$(openssl rand -hex 32)" | gcloud secrets create forecast-ip-hash-secret --data-file=-
printf '%s' "$TOMTOM_API_KEY"         | gcloud secrets create forecast-tomtom-key     --data-file=-
printf '%s' 'jdbc:postgresql://…neon.tech/forecast?sslmode=require' \
                                      | gcloud secrets create forecast-db-url         --data-file=-
printf '%s' "$NEON_PASSWORD"          | gcloud secrets create forecast-db-password    --data-file=-
```

Then two service accounts, which is the detail worth getting right first time:

- the **deploy** account GitHub impersonates needs `run.admin`,
  `artifactregistry.writer` and `iam.serviceAccountUser`;
- the **runtime** account the revision runs as (the default compute account, unless you
  pass `--service-account`) needs `secretmanager.secretAccessor` — it is what actually
  reads the five secrets, and `gcloud run deploy` checks that at deploy time, so granting
  the deploy account instead fails with a permissions error that names the wrong
  identity.

Plus a Workload Identity pool whose provider is restricted to this repository — the
restriction is the point, since without it any repository could mint the same token.
Finally, five **repository variables**
(Settings → Secrets and variables → Actions → Variables) — none are credentials:

| Variable | Example |
|---|---|
| `GCP_PROJECT_ID` | `forecast-470112` |
| `GCP_REGION` | `us-central1` |
| `GCP_WIF_PROVIDER` | `projects/123456789/locations/global/workloadIdentityPools/github/providers/forecastapp` |
| `GCP_DEPLOY_SA` | `deploy@forecast-470112.iam.gserviceaccount.com` |
| `DB_USER` | `forecast` |

**The seeded history is imported, not shipped in the image**: run the importer against
Neon from a laptop once, rather than putting `data/` in production. After that, the
weekly sweep keeps Neon current on its own (next section).

```bash
DB_URL=... DB_USER=... DB_PASSWORD=... ./gradlew :service:bootRun --args='--import-seed'
```

The workflow's last step asks Cloud Run directly for `/api/quota` and expects a 403. It
passes from the very first revision, because the origin secret is already in Secret
Manager and the service refuses any request without it. A 200 there would mean the
origin trusts `CF-Connecting-IP` from anyone, so the per-IP limits could be bypassed by
sending a different one each time. That is also why nothing works through the API
hostname until the Transform Rule below exists.

The rest happens outside the workflow, once, in this order:

1. **Verify the domain with Google.** `whentoleave.me` is verified in Google Search
   Console through a TXT record in Cloudflare DNS. Cloud Run will not map a hostname on
   an unverified domain.
2. **Map the hostname to the service.**

   ```bash
   gcloud beta run domain-mappings create --service forecast-service \
     --domain api.whentoleave.me --region "$REGION"
   ```
3. **Add the DNS record grey-clouded first.** A CNAME `api` → `ghs.googlehosted.com`,
   set to **DNS only**. Google issues the certificate by reaching the hostname itself, and
   through Cloudflare's proxy it cannot, so a proxied record leaves the mapping waiting
   on a certificate indefinitely. Here it took about 50 minutes. Once
   `gcloud beta run domain-mappings describe` reports the certificate ready, switch the
   record to **Proxied**. Every rule below applies only to proxied traffic.
4. **Transform Rule `origin-secret`.** Rules → Transform Rules → Modify Request Header,
   on hostname `api.whentoleave.me`: set `X-Origin-Secret` to the value of
   `forecast-origin-secret` (the wizard keeps it in `.env` as `ORIGIN_SECRET`).
5. **Rate-limit rule `api-per-ip`.** A Cloudflare rate limiting rule on the same
   hostname, counted per IP, action Block. It stops a flood at the edge, before it costs
   a Cloud Run instance; the service's own per-IP counters still decide the daily limits.
6. **Spend cap.** A $1 spend cap in GCP Billing, scoped to Cloud Run.
7. **Artifact Registry cleanup.** Every deploy pushes an image, and storage past the free
   0.5 GB is billed. Keep the five most recent, delete anything older than a day:

   ```bash
   cat > cleanup.json <<'JSON'
   [
     {"name": "keep-last-5", "action": {"type": "Keep"},
      "mostRecentVersions": {"keepCount": 5}},
     {"name": "delete-old", "action": {"type": "Delete"},
      "condition": {"tagState": "any", "olderThan": "1d"}}
   ]
   JSON
   gcloud artifacts repositories set-cleanup-policies forecast \
     --location="$REGION" --policy=cleanup.json --no-dry-run
   ```
8. **Point the site at it.** Set `VITE_API_BASE=https://api.whentoleave.me` in the Pages
   environment, as a Text variable for Production, in the same change that adds the hostname to `connect-src`
   in `web/public/_headers`. Otherwise the CSP blocks every lookup and the panel reports
   an unreachable service.

`scripts/gcp-setup.sh` covers the GCP setup above and ends by listing these steps. It
performs none of them.

## Loading each sweep into Neon

The live API reads Neon, not `data/samples/`. So after the weekly `sample` workflow
commits a sweep, its last step imports it:

```bash
./gradlew :service:bootRun --args='--import-seed --spring.profiles.active=import'
```

The `import` profile runs with no web server and no Flyway, and exits when the import is
done. The importer skips rows it already has, so a missed week is caught up by the next
run.

This is the one place a database credential passes through Actions, so it belongs to a
role that can do almost nothing. `forecast_import` can read and add rows to `corridor` and
`sample`, which hold public traffic data. It can't reach the per-IP counters, the quota
or the kill switch, and it can't update, delete or create anything. `ImportRoleTest`
checks each of those limits against the real grants script in CI.

Once:

1. In the Neon SQL Editor, connected as the role that owns the tables, run
   `scripts/neon-import-role.sql` with its placeholder replaced by a generated password
   (`openssl rand -hex 32`). Create the role in SQL, not in the Neon console: roles made
   in the console join `neon_superuser`, which is exactly the reach this one must not
   have.
2. Add two repository secrets:

   ```bash
   gh secret set SEED_DB_URL        # the same JDBC URL as forecast-db-url
   gh secret set SEED_DB_PASSWORD   # the password from step 1
   ```

Until both are set, the step skips with a warning rather than failing the sweep.
