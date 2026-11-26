import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter } from 'react-router-dom';
import { ApiError } from './api/client';
import { App } from './App';
import { reloadForNewVersion } from './components/ErrorBoundary';
import './styles/styles.css';
import './styles/workspace.css';
import './styles/editor.css';
import './styles/code-import.css';
import './styles/app.css';
import './styles/create.css';
import './styles/timeline.css';
import './styles/community.css';
import './styles/ecosystem.css';
import './styles/dashboard.css';
import './styles/logs.css';
import './styles/analytics.css';
import './styles/responsive.css';

// Vite 动态导入分包失败（通常是发版后旧文件已被替换）：自动刷新一次拿新版本
window.addEventListener('vite:preloadError', (event) => {
  event.preventDefault();
  reloadForNewVersion();
});

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 10_000,
      refetchOnWindowFocus: false,
      retry: (count, error) => !(error instanceof ApiError && error.status >= 400 && error.status < 500) && count < 2,
    },
  },
});

createRoot(document.getElementById('app')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
);
