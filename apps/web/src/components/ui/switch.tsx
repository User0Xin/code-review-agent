import { Switch as DubSwitch } from "@dub/ui";
import { type ComponentProps } from "react";

type SwitchProps = Omit<ComponentProps<typeof DubSwitch>, "fn"> & {
  onCheckedChange?: (checked: boolean) => void;
};

export function Switch({ onCheckedChange, ...props }: SwitchProps) {
  return <DubSwitch {...props} fn={onCheckedChange} />;
}
