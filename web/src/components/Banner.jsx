/**
 * One line explaining a limit or a failure, in the one shape both places use.
 *
 * Two callers — above a degraded grid, and under the lookup form — because a limit
 * arrives both ways: as a `notice` on a 200 that could not be filled, and as an `error`
 * on a refusal. Nothing is hidden and nothing shouts: `tone` is the only difference, and
 * a stated limit is styled as the normal state it is.
 */
export default function Banner({ notice }) {
  if (!notice) return null;
  return (
    <p className={`banner is-${notice.tone}`} role="status">
      {notice.text}
    </p>
  );
}
