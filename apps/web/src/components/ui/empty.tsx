import { cn } from "@/lib/utils";
import { type ComponentProps } from "react";

export function Empty({ className, ...props }: ComponentProps<"div">) {
  return (
    <div
      {...props}
      className={cn(
        "flex w-full min-w-0 flex-1 flex-col items-center justify-center gap-4 text-center text-balance",
        className,
      )}
    />
  );
}
