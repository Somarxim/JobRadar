import { clsx, type ClassValue } from "clsx"
import { twMerge } from "tailwind-merge"

/** cn：合并条件类名 + 解决 Tailwind 类冲突（后者覆盖前者），shadcn 组件的拼装基石 */
export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}
