import { useEffect } from 'react';
import { Navigate, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { LightLayout } from './components/LightLayout';
import { Toasts } from './components/Toasts';
import { CodeImportDialog } from './import/CodeImportDialog';
import { DataSourceDialog } from './components/DataSourceDialog';
import { AppsPage } from './pages/AppsPage';
import { ChatPage } from './pages/ChatPage';
import { CreatePage } from './pages/CreatePage';
import { HistoryPage } from './pages/HistoryPage';
import { CommunityPage } from './pages/CommunityPage';
import { WorkPage } from './pages/WorkPage';
import { AuthGate, LoginPage, SsoCallbackPage } from './pages/LoginPage';
import { RecoverPage } from './pages/RecoverPage';
import { WorkbenchRoute } from './workbench/Workbench';
import { ErrorBoundary } from './components/ErrorBoundary';
import { ConfirmHost } from './components/ConfirmDialog';

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
      <ErrorBoundary area="这个页面" page key={location.pathname}>
      <Routes>
        <Route path="/" element={<Navigate to="/create" replace />} />
        <Route path="/login" element={<LoginPage />} />
        <Route path="/access_token" element={<SsoCallbackPage />} />
        <Route path="/recover" element={<RecoverPage />} />
        <Route path="/apps/:id" element={<WorkbenchRoute />} />
        <Route element={<LightLayout />}>
          <Route path="/create" element={<CreatePage />} />
          <Route path="/apps" element={<AppsPage />} />
          <Route path="/history" element={<HistoryPage />} />
          <Route path="/community" element={<CommunityPage />} />
          <Route path="/community/:id" element={<WorkPage />} />
          <Route path="/chat/:id" element={<ChatPage />} />
        </Route>
        <Route path="*" element={<Navigate to="/create" replace />} />
      </Routes>
      </ErrorBoundary>
      <CodeImportDialog />
      <DataSourceDialog />
      <ConfirmHost />
      <Toasts />
    </AuthGate>
  );
}
