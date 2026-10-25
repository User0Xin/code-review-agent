import { z } from "zod";

export const httpUrl = z
  .string()
  .url()
  .refine((s) => {
    const u = new URL(s);
    return (
      ["http:", "https:"].includes(u.protocol) && !u.username && !u.password && !u.search && !u.hash
    );
  }, "需要不含凭据、查询参数或片段的 HTTP(S) 地址");
