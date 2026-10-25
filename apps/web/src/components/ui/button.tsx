import { Button as DubButton, buttonVariants } from "@dub/ui";
import { type ComponentProps } from "react";
import { cn } from "@/lib/utils";

const variants = {
  default: "primary",
  outline: "secondary",
  secondary: "secondary",
  ghost: "outline",
  destructive: "danger",
} as const;

const sizes = {
  default: "",
  sm: "h-8 px-3",
  xs: "h-7 px-2 text-xs",
  "icon-sm": "size-8 p-0",
  "icon-xs": "size-7 p-0",
} as const;

type ButtonProps = ComponentProps<"button"> & {
  variant?: keyof typeof variants;
  size?: keyof typeof sizes;
};

// Translate the application's API; colors and interaction states come from Dub.
export function Button({
  children,
  className,
  variant = "default",
  size = "default",
  type = "button",
  ...props
}: ButtonProps) {
  return (
    <DubButton
      {...props}
      type={type}
      variant={variants[variant]}
      data-slot="button"
      className={cn("w-auto shrink-0 [&_svg]:size-4 [&_svg]:shrink-0", sizes[size], className)}
      text={children}
      textWrapperClassName="contents"
    />
  );
}

// Dub exports its variants for links that need the same appearance as buttons.
export function ButtonLink({ className, ...props }: ComponentProps<"a">) {
  return (
    <a
      {...props}
      className={cn(
        "inline-flex h-8 shrink-0 items-center justify-center gap-2 whitespace-nowrap rounded-lg border px-3 text-sm [&_svg]:size-4",
        buttonVariants({ variant: "secondary" }),
        className,
      )}
    />
  );
}
