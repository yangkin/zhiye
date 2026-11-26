import { Suspense, useEffect } from 'react';
import { Navigate, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { LightLayout, RouteFallback } from './components/LightLayout';
import { Toasts } from './components/Toasts';
import { CodeImportDialog } from './import/CodeImportDialog';
import { DataSourceDialog } from './components/DataSourceDialog';
import { CreatePage } from './pages/CreatePage';
import { AuthGate, LoginPage, SsoCallbackPage } from './pages/LoginPage';
import { RecoverPage } from './pages/RecoverPage';
import { ErrorBoundary } from './components/ErrorBoundary';
import { ConfirmHost } from './components/ConfirmDialog';
import {
  AdminPage,
  AppsPage,
  ChatPage,
  CommunityPage,
  DashboardPage,
  EcosystemPage,
  HistoryPage,
  LogsPage,
  WorkbenchRoute,
  WorkPage,
} from './lib/routes';

export function App() {
  const navigate = useNavigate();
  const location = useLocation();
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        navigate('/create');
        setTimeout(() => document.getElementById('home-prompt')?.focus(), 30);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [navigate]);

  return (
    <AuthGate>
      <Routes>
        <Route path="/" element={<Navigate to="/create" replace />} />
        <Route path="/login" element={<LoginPage />} />
        <Route path="/access_token" element={<SsoCallbackPage />} />
        <Route path="/recover" element={<RecoverPage />} />
        <Route
          path="/apps/:id"
          element={
            <ErrorBoundary area="工作台" page key={location.pathname}>
              <Suspense fallback={<RouteFallback />}>
                <WorkbenchRoute />
              </Suspense>
            </ErrorBoundary>
          }
        />
        <Route element={<LightLayout />}>
          <Route path="/create" element={<CreatePage />} />
          <Route path="/apps" element={<AppsPage />} />
          <Route path="/ecosystem" element={<EcosystemPage />} />
          <Route path="/history" element={<HistoryPage />} />
          <Route path="/community" element={<CommunityPage />} />
          <Route path="/community/:id" element={<WorkPage />} />
          <Route path="/dashboard" element={<DashboardPage />} />
          <Route path="/admin" element={<AdminPage />} />
          <Route path="/logs" element={<LogsPage />} />
          <Route path="/chat/:id" element={<ChatPage />} />
        </Route>
        <Route path="*" element={<Navigate to="/create" replace />} />
      </Routes>
      <CodeImportDialog />
      <DataSourceDialog />
      <ConfirmHost />
      <Toasts />
    </AuthGate>
  );
}
