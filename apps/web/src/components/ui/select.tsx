import { Combobox } from "@dub/ui";
import { ChevronDown } from "lucide-react";
import { useEffect, useRef, useState, type ComponentProps } from "react";

import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

type SelectOption = {
  value: string;
  label: string;
  disabled?: boolean;
};

type SelectProps = Omit<ComponentProps<"button">, "value" | "onChange" | "children"> & {
  options: SelectOption[];
  value: string;
  onValueChange: (value: string) => void;
  placeholder?: string;
  searchable?: boolean;
  searchPlaceholder?: string;
  size?: "sm" | "default";
};

export function Select({
  options,
  value,
  onValueChange,
  placeholder = "请选择",
  searchable = false,
  searchPlaceholder = "搜索选项…",
  size = "default",
  className,
  disabled,
  ref,
  onKeyDown,
  ...props
}: SelectProps) {
  const triggerRef = useRef<HTMLButtonElement>(null);
  const [open, setOpen] = useState(false);
  const isOpen = open && !disabled;
  const items = options.map((option) => ({
    ...option,
    disabledTooltip: option.disabled ? "此选项已禁用" : undefined,
  }));
  const selected = items.find((option) => option.value === value) ?? null;

  useEffect(() => {
    if (!isOpen) {
      return;
    }
    const frame = requestAnimationFrame(() => {
      const trigger = triggerRef.current;
      const contentId = trigger?.getAttribute("aria-controls");
      const content = contentId ? document.getElementById(contentId) : null;
      const command = content?.querySelector<HTMLElement>("[cmdk-root]");
      if (!trigger || !content || !command) {
        return;
      }
      const label =
        trigger.getAttribute("aria-label") ||
        Array.from(trigger.labels ?? [], (label) => label.textContent).join(" ") ||
        placeholder;
      content.setAttribute("aria-label", label);
      const input = command.querySelector<HTMLInputElement>("[cmdk-input]");
      if (input) {
        input.setAttribute("aria-label", `搜索${label}`);
      } else {
        // Dub omits its input for short lists. Focus the command root so cmdk
        // still owns arrow-key navigation and Enter selection in this mode.
        command.tabIndex = -1;
        command.style.outline = "none";
      }
      (input ?? command).focus({ preventScroll: true });
    });
    return () => cancelAnimationFrame(frame);
  }, [isOpen, placeholder]);

  return (
    <Combobox
      options={items}
      selected={selected}
      onSelect={(option) => {
        if (option.value !== value) {
          onValueChange(option.value);
        }
      }}
      open={isOpen}
      onOpenChange={setOpen}
      hideSearch={!searchable}
      searchPlaceholder={searchPlaceholder}
      emptyState="没有匹配的选项"
      matchTriggerWidth
      forceDropdown
      popoverProps={{
        // Dub's mobile scroller assumes a full-width drawer. Keep it inside
        // the anchored menu when forceDropdown is enabled on small screens.
        contentClassName:
          "w-[var(--radix-popover-trigger-width)] min-w-40 max-w-[calc(100vw-1rem)] overflow-hidden [&_.w-screen]:w-full",
      }}
      trigger={
        <Button
          {...props}
          ref={(element) => {
            triggerRef.current = element;
            if (typeof ref === "function") {
              return ref(element);
            }
            if (ref) {
              ref.current = element;
            }
          }}
          type="button"
          variant="outline"
          size={size}
          disabled={disabled}
          data-select-trigger=""
          className={cn("group w-full min-w-0 justify-between gap-2 px-3", className)}
          onKeyDown={(event) => {
            onKeyDown?.(event);
            if (!event.defaultPrevented && ["ArrowDown", "ArrowUp"].includes(event.key)) {
              event.preventDefault();
              setOpen(true);
            }
          }}
        >
          <span className={cn("truncate text-left", !value && "text-neutral-500")}>
            {selected?.label ?? placeholder}
          </span>
          <ChevronDown
            aria-hidden="true"
            className="text-neutral-400 transition-transform group-data-[state=open]:rotate-180"
          />
        </Button>
      }
    />
  );
}
