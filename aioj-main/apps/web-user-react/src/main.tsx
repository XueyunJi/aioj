import React from "react";
import ReactDOM from "react-dom/client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { isAuthBoundaryError, setApiErrorMessageResolver } from "@aioj/api-client";
import { getStoredLocale, resolveApiErrorMessageForLocale } from "@aioj/i18n/vanilla";
import { AppRouter } from "./router";
import { I18nProvider } from "./lib/i18n";
import "./styles/tokens.css";

setApiErrorMessageResolver((code, fallback, context) => resolveApiErrorMessageForLocale(code, fallback, getStoredLocale(), context));

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      retry: (failureCount, error) => !isAuthBoundaryError(error) && failureCount < 1
    }
  }
});

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <I18nProvider>
      <QueryClientProvider client={queryClient}>
        <AppRouter />
      </QueryClientProvider>
    </I18nProvider>
  </React.StrictMode>
);
