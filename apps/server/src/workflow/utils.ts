export const timestamp = () => new Date().toISOString();
export const clone = <T>(value: T): T => structuredClone(value);
export const errorText = (error: unknown) =>
  error instanceof Error ? error.message : String(error);
export const normalized = (value: string) => value.trim().toLowerCase().replace(/\s+/g, " ");
export const ownMarker = (body: string) => /<!--\s*review-agent:/.test(body);
