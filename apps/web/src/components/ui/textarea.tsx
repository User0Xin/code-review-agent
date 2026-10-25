import { cn } from "@/lib/utils";
import { type ComponentProps } from "react";

// Dub uses native textarea/select controls with its Tailwind forms theme.
export function Textarea({ className, ...props }: ComponentProps<"textarea">) {
  return (
    <textarea
      {...props}
      data-slot="textarea"
      className={cn(
        "min-h-24 w-full rounded-md border border-neutral-300 text-sm text-neutral-900 placeholder-neutral-400 focus:border-neutral-500 focus:outline-none focus:ring-neutral-500 disabled:cursor-not-allowed disabled:bg-neutral-100 disabled:text-neutral-500",
        className,
      )}
    />
  );
}
