import * as React from "react";
import { Slot } from "@radix-ui/react-slot";
import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "../lib/cn";

export const buttonVariants = cva(
  "inline-flex shrink-0 flex-row items-center justify-center gap-2 whitespace-nowrap rounded-xl text-sm font-medium leading-5 transition-colors [word-break:keep-all] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--oj-focus)] disabled:pointer-events-none disabled:opacity-50 [&>svg]:shrink-0",
  {
    variants: {
      variant: {
        primary: "bg-[var(--oj-primary)] text-white hover:bg-[var(--oj-primary-strong)]",
        secondary: "bg-[var(--oj-surface-muted)] text-[var(--oj-ink)] hover:bg-[var(--oj-border-soft)]",
        outline: "border border-[var(--oj-border)] bg-white text-[var(--oj-ink)] hover:bg-[var(--oj-surface-muted)]",
        ghost: "text-[var(--oj-ink-muted)] hover:bg-[var(--oj-surface-muted)] hover:text-[var(--oj-ink)]"
      },
      size: {
        sm: "h-8 px-3",
        md: "h-10 px-4",
        lg: "h-11 px-5"
      }
    },
    defaultVariants: {
      variant: "primary",
      size: "md"
    }
  }
);

export interface ButtonProps
  extends React.ButtonHTMLAttributes<HTMLButtonElement>,
    VariantProps<typeof buttonVariants> {
  asChild?: boolean;
}

export const Button = React.forwardRef<HTMLButtonElement, ButtonProps>(
  ({ className, variant, size, asChild = false, ...props }, ref) => {
    const Comp = asChild ? Slot : "button";
    return <Comp ref={ref} className={cn(buttonVariants({ variant, size }), className)} {...props} />;
  }
);

Button.displayName = "Button";
