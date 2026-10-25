import { Input as DubInput } from "@dub/ui";
import { type ComponentProps } from "react";
import { cn } from "@/lib/utils";

export function Input({ className, ...props }: ComponentProps<typeof DubInput>) {
  // Keep the input and Dub's password control aligned within the form column.
  return <DubInput {...props} className={cn("max-w-none", className)} />;
}
