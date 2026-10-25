"use client";

import { cn } from "@/lib/utils";
import { TabSelect } from "@dub/ui";
import {
  Children,
  createContext,
  isValidElement,
  useContext,
  useId,
  useLayoutEffect,
  useRef,
  useState,
  type ComponentProps,
  type ReactElement,
} from "react";

type TabsContextValue = {
  id: string;
  value: string;
  select: (value: string) => void;
  orientation: "horizontal" | "vertical";
};

const TabsContext = createContext<TabsContextValue | null>(null);

function useTabs() {
  const context = useContext(TabsContext);
  if (!context) {
    throw new Error("Tabs components must be rendered inside Tabs.");
  }
  return context;
}

type TabsProps = Omit<ComponentProps<"div">, "defaultValue"> & {
  defaultValue?: string;
  value?: string;
  onValueChange?: (value: string) => void;
  orientation?: "horizontal" | "vertical";
};

function Tabs({
  className,
  orientation = "horizontal",
  defaultValue = "",
  value: controlledValue,
  onValueChange,
  ...props
}: TabsProps) {
  const id = useId();
  const [localValue, setLocalValue] = useState(defaultValue);
  const value = controlledValue ?? localValue;

  function select(nextValue: string) {
    if (nextValue === value) {
      return;
    }
    if (controlledValue === undefined) {
      setLocalValue(nextValue);
    }
    onValueChange?.(nextValue);
  }

  return (
    <TabsContext.Provider value={{ id, value, select, orientation }}>
      <div
        data-slot="tabs"
        data-orientation={orientation}
        className={cn(
          "cn-tabs flex min-h-0",
          orientation === "horizontal" && "flex-col",
          className,
        )}
        {...props}
      />
    </TabsContext.Provider>
  );
}

type TabsListVariant = { variant?: "default" | "line" };

function tabsListVariants({ variant = "default" }: TabsListVariant = {}) {
  return cn("cn-tabs-list w-fit", `cn-tabs-list-variant-${variant}`);
}

type TabsTriggerProps = Pick<ComponentProps<"button">, "children" | "className" | "disabled"> & {
  value: string;
};

function TabsList({
  className,
  variant = "default",
  children,
  onKeyDown,
  ...props
}: ComponentProps<"div"> & TabsListVariant) {
  const { id, value, select, orientation } = useTabs();
  const listRef = useRef<HTMLDivElement>(null);
  const triggers = Children.toArray(children).filter(
    (child): child is ReactElement<TabsTriggerProps> =>
      isValidElement<TabsTriggerProps>(child) && child.type === TabsTrigger,
  );
  const options = triggers.map((trigger) => ({
    id: trigger.props.value,
    label: trigger.props.children,
  }));

  // Dub owns the buttons and indicator. Its API does not expose button props,
  // so add the tab/panel relationships and roving focus after each render.
  useLayoutEffect(() => {
    const buttons = Array.from(
      listRef.current?.querySelectorAll<HTMLButtonElement>(".dub-tabs-select > div > button") ?? [],
    );
    buttons.forEach((button, index) => {
      const trigger = triggers[index];
      if (!trigger) {
        return;
      }
      const selected = trigger.props.value === value;
      button.id = `${id}-tab-${trigger.props.value}`;
      button.setAttribute("role", "tab");
      button.setAttribute("aria-controls", `${id}-panel-${trigger.props.value}`);
      button.dataset.slot = "tabs-trigger";
      button.tabIndex = selected ? 0 : -1;
      button.disabled = trigger.props.disabled ?? false;
      button.classList.add("cn-tabs-trigger", "inline-flex", "items-center", "gap-2");
      if (button.parentElement) {
        button.parentElement.className = cn("relative", trigger.props.className);
      }
    });

    // A responsive tab can disappear while selected. Keep a visible tab active
    // so its panel remains labelled and the tab list stays keyboard reachable.
    const syncVisibleSelection = () => {
      const selectedButton = buttons.find(
        (button) => button.getAttribute("aria-selected") === "true",
      );
      if (
        selectedButton &&
        !selectedButton.disabled &&
        selectedButton.getClientRects().length > 0
      ) {
        return;
      }
      const nextIndex = buttons.findIndex(
        (button) => !button.disabled && button.getClientRects().length > 0,
      );
      const nextTrigger = triggers[nextIndex];
      if (!nextTrigger) {
        return;
      }
      if (document.activeElement === selectedButton) {
        buttons[nextIndex]?.focus();
      }
      select(nextTrigger.props.value);
    };
    const observer = new ResizeObserver(syncVisibleSelection);
    buttons.forEach((button) => observer.observe(button));
    return () => observer.disconnect();
  });

  return (
    <div
      {...props}
      ref={listRef}
      role="tablist"
      aria-orientation={orientation}
      data-slot="tabs-list"
      data-variant={variant}
      className={cn(tabsListVariants({ variant }), className)}
      onKeyDown={(event) => {
        onKeyDown?.(event);
        if (event.defaultPrevented || event.altKey || event.ctrlKey || event.metaKey) {
          return;
        }
        const buttons = Array.from(
          event.currentTarget.querySelectorAll<HTMLButtonElement>('[role="tab"]'),
        ).filter((button) => !button.disabled && button.getClientRects().length > 0);
        const index = buttons.findIndex((button) => button === event.target);
        if (index < 0) {
          return;
        }
        const rtl = getComputedStyle(event.currentTarget).direction === "rtl";
        const previous = orientation === "vertical" ? "ArrowUp" : rtl ? "ArrowRight" : "ArrowLeft";
        const next = orientation === "vertical" ? "ArrowDown" : rtl ? "ArrowLeft" : "ArrowRight";
        const target =
          event.key === "Home"
            ? buttons[0]
            : event.key === "End"
              ? buttons.at(-1)
              : event.key === previous
                ? buttons[(index - 1 + buttons.length) % buttons.length]
                : event.key === next
                  ? buttons[(index + 1) % buttons.length]
                  : undefined;
        if (!target) {
          return;
        }
        event.preventDefault();
        target.focus();
        target.click();
      }}
    >
      <TabSelect
        options={options}
        selected={value}
        onSelect={select}
        className={cn("dub-tabs-select", orientation === "vertical" && "flex-col")}
      />
    </div>
  );
}

// TabsList uses these declarative children as Dub TabSelect options.
function TabsTrigger({ children }: TabsTriggerProps) {
  return <>{children}</>;
}

function TabsContent({
  className,
  value,
  children,
  ...props
}: ComponentProps<"div"> & { value: string }) {
  const tabs = useTabs();
  const active = tabs.value === value;

  return (
    <div
      {...props}
      id={`${tabs.id}-panel-${value}`}
      role="tabpanel"
      aria-labelledby={`${tabs.id}-tab-${value}`}
      hidden={!active}
      tabIndex={0}
      data-slot="tabs-content"
      className={cn("cn-tabs-content flex-1 outline-none", className)}
    >
      {active ? children : null}
    </div>
  );
}

export { Tabs, TabsContent, TabsList, tabsListVariants, TabsTrigger };
