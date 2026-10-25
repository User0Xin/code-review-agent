import { IntegrationError } from "./integration-error.js";

export type PlatformObject = Record<string, unknown>;
export const readObject = (value: unknown): PlatformObject => {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new IntegrationError("平台返回了无效对象");
  }
  return value as PlatformObject;
};
export const readString = (value: unknown) => (typeof value === "string" ? value : "");
export const readNumber = (value: unknown) =>
  typeof value === "number" && Number.isFinite(value) ? value : 0;
export const readPositiveNumber = (value: unknown) =>
  typeof value === "number" && value > 0 ? value : undefined;
export const readId = (value: unknown) =>
  typeof value === "number" || typeof value === "string" ? String(value) : "";
export const readArray = (value: unknown): unknown[] => {
  if (!Array.isArray(value)) {
    throw new IntegrationError("平台返回了无效列表");
  }
  return value;
};
