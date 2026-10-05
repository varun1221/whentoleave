/**
 * A fake `POST /api/lookup` that answers as a stream of server-sent events.
 *
 * Shared because the api tests and the panel tests both have to read the same wire
 * format, and a fake copied per file drifts from the service the moment either changes.
 */

/** One event as `GridEvents` writes it: name, JSON data, blank line. */
export const sseEvent = (name, grid) => `event: ${name}\ndata: ${JSON.stringify(grid)}\n\n`;

/**
 * A response whose body arrives in these chunks. Only the members `requestLookup` reads.
 * A chunk need not be a whole event: a network splits wherever it likes.
 */
export const eventStreamResponse = (...chunks) => {
  const encoded = chunks.map((chunk) => new TextEncoder().encode(chunk));
  return {
    ok: true,
    status: 200,
    headers: { get: () => "text/event-stream;charset=UTF-8" },
    body: {
      getReader: () => ({
        read: async () =>
          encoded.length
            ? { done: false, value: encoded.shift() }
            : { done: true, value: undefined },
      }),
    },
  };
};
