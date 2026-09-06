// Match PublicDocuments.search; the tag catalogue passes its separate 320000 bound.
export function pageOffset(
  value: string | string[] | undefined,
  maximum = 10000,
): string {
  if (typeof value !== "string" || !/^[0-9]+$/.test(value)) return "0";
  const offset = Number(value);
  return Number.isSafeInteger(offset) && offset <= maximum
    ? String(offset)
    : "0";
}
