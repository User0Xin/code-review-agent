"use client";

import { Modal } from "@dub/ui";
import * as DialogPrimitive from "@radix-ui/react-dialog";
import { XIcon } from "lucide-react";
import * as React from "react";

import { Button } from "@/components/ui/button";
import { DubRouterProvider } from "@/components/ui/dub-router-provider";
import { cn } from "@/lib/utils";

type DialogContextValue = {
  open: boolean;
  setOpen: React.Dispatch<React.SetStateAction<boolean>>;
  titleId: string;
  descriptionId: string;
};

const DialogContext = React.createContext<DialogContextValue | null>(null);

function useDialog() {
  const context = React.useContext(DialogContext);
  if (!context) {
    throw new Error("Dialog components must be rendered inside Dialog.");
  }
  return context;
}

function Dialog({
  children,
  open,
  defaultOpen = false,
  onOpenChange,
}: {
  children: React.ReactNode;
  open?: boolean;
  defaultOpen?: boolean;
  onOpenChange?: (open: boolean) => void;
}) {
  const [internalOpen, setInternalOpen] = React.useState(defaultOpen);
  const isOpen = open ?? internalOpen;
  const titleId = React.useId();
  const descriptionId = React.useId();
  const restoreFrame = React.useRef<number | undefined>(undefined);
  const setOpen = React.useCallback<React.Dispatch<React.SetStateAction<boolean>>>(
    (value) => {
      const nextOpen = typeof value === "function" ? value(isOpen) : value;
      if (open === undefined) {
        setInternalOpen(nextOpen);
      }
      onOpenChange?.(nextOpen);
    },
    [isOpen, onOpenChange, open],
  );

  // Dub suppresses Radix's return-focus behavior for route-backed modals. These
  // dialogs are controlled in place, so return to the button that opened them.
  React.useLayoutEffect(() => {
    if (!isOpen) {
      return;
    }
    if (restoreFrame.current !== undefined) {
      cancelAnimationFrame(restoreFrame.current);
    }
    const previouslyFocused = document.activeElement;
    return () => {
      restoreFrame.current = requestAnimationFrame(() => {
        if (previouslyFocused instanceof HTMLElement && previouslyFocused.isConnected) {
          previouslyFocused.focus({ preventScroll: true });
        }
      });
    };
  }, [isOpen]);

  return (
    <DialogContext.Provider value={{ open: isOpen, setOpen, titleId, descriptionId }}>
      {children}
    </DialogContext.Provider>
  );
}

function DialogContent({
  className,
  children,
  showCloseButton = true,
  ...props
}: React.ComponentProps<"div"> & { showCloseButton?: boolean }) {
  const { open, setOpen } = useDialog();
  return (
    <DubRouterProvider>
      <Modal
        showModal={open}
        setShowModal={setOpen}
        className={cn("max-h-[calc(100dvh-2rem)]", className)}
      >
        <DialogBody {...props}>
          {children}
          {showCloseButton && (
            <Button
              type="button"
              variant="ghost"
              size="icon-sm"
              className="absolute right-0 top-0"
              aria-label="关闭"
              onClick={() => setOpen(false)}
            >
              <XIcon aria-hidden="true" />
            </Button>
          )}
        </DialogBody>
      </Modal>
    </DubRouterProvider>
  );
}

function DialogBody({ children, ...props }: React.ComponentProps<"div">) {
  const ref = React.useRef<HTMLDivElement>(null);

  React.useLayoutEffect(() => {
    const content = ref.current;
    if (!content) {
      return;
    }
    const dialog = content.closest('[role="dialog"]');
    const title = content.querySelector<HTMLElement>('[data-slot="dialog-title"]');
    const description = content.querySelector<HTMLElement>('[data-slot="dialog-description"]');

    // Modal owns its Radix/Vaul Content and supplies generic hidden labels.
    // Point that content at this workflow's visible title and description.
    if (title) {
      dialog?.setAttribute("aria-labelledby", title.id);
    }
    if (description) {
      dialog?.setAttribute("aria-describedby", description.id);
    } else {
      dialog?.removeAttribute("aria-describedby");
    }

    const frame = requestAnimationFrame(() => {
      if (content.contains(document.activeElement)) {
        return;
      }
      const candidates = content.querySelectorAll<HTMLElement>(
        "[autofocus], input, select, textarea, button, a[href], [tabindex]",
      );
      const firstFocusable = Array.from(candidates).find(
        (element) =>
          element.tabIndex >= 0 &&
          !element.matches(":disabled") &&
          element.getClientRects().length > 0,
      );
      (firstFocusable ?? content).focus({ preventScroll: true });
    });
    return () => cancelAnimationFrame(frame);
  }, []);

  return (
    <div
      {...props}
      ref={ref}
      data-slot="dialog-content"
      tabIndex={-1}
      className="relative flex flex-col gap-6 outline-none"
    >
      {children}
    </div>
  );
}

function DialogHeader({ className, ...props }: React.ComponentProps<"div">) {
  return (
    <div
      data-slot="dialog-header"
      className={cn("flex flex-col gap-2 pr-8", className)}
      {...props}
    />
  );
}

function DialogFooter({
  className,
  showCloseButton = false,
  children,
  ...props
}: React.ComponentProps<"div"> & { showCloseButton?: boolean }) {
  const { setOpen } = useDialog();
  return (
    <div
      data-slot="dialog-footer"
      className={cn(
        "flex flex-col-reverse gap-2 border-t border-neutral-200 pt-5 sm:flex-row sm:justify-end",
        className,
      )}
      {...props}
    >
      {children}
      {showCloseButton && (
        <Button type="button" variant="outline" onClick={() => setOpen(false)}>
          关闭
        </Button>
      )}
    </div>
  );
}

function DialogTitle({
  className,
  id,
  ...props
}: React.ComponentProps<typeof DialogPrimitive.Title>) {
  const { titleId } = useDialog();
  return (
    <DialogPrimitive.Title
      {...props}
      id={id ?? titleId}
      data-slot="dialog-title"
      className={cn("text-lg font-medium text-neutral-900", className)}
    />
  );
}

function DialogDescription({
  className,
  id,
  ...props
}: React.ComponentProps<typeof DialogPrimitive.Description>) {
  const { descriptionId } = useDialog();
  return (
    <DialogPrimitive.Description
      {...props}
      id={id ?? descriptionId}
      data-slot="dialog-description"
      className={cn("text-sm leading-relaxed text-neutral-500", className)}
    />
  );
}

export { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle };
