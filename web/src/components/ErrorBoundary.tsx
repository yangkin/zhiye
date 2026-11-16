import { Component, lazy, type ComponentType, type ReactNode } from 'react';
import { Icon } from '../lib/icons';

/** 按需加载的模块下载失败（网络抖动、发版后旧文件失效）时，React.lazy 会缓存失败结果；这里在重试时换一个新的 lazy 重新下载 */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
export function retryableLazy<T extends ComponentType<any>>(load: () => Promise<{ default: T }>) {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  let Lazy: ComponentType<any> = lazy(load);
  const Wrapper = ((props: object) => <Lazy {...props} />) as unknown as T & { reload: () => void };
  Wrapper.reload = () => {
    Lazy = lazy(load);
  };
  return Wrapper;
}

const isChunkError = (e: Error) => /dynamically imported module|Importing a module script failed|Loading chunk|error loading dynamically/i.test(e.message);

interface Props {
  children: ReactNode;
  /** 出错区域的名字，用在提示里，比如「代码面板」 */
  area: string;
  /** 重试前的清理，比如换一个新的 lazy */
  onReset?: () => void;
  /** 整页级别：多一个「回到创造」 */
  page?: boolean;
}

/** 某一块出错时只替换这一块，导航、对话等其他区域照常可用 */
export class ErrorBoundary extends Component<Props, { error: Error | null }> {
  state = { error: null as Error | null };

  static getDerivedStateFromError(error: Error) {
    return { error };
  }

  componentDidCatch(error: Error) {
    console.error(`[${this.props.area}]`, error);
  }

  reset = () => {
    this.props.onReset?.();
    this.setState({ error: null });
  };

  render() {
    const { error } = this.state;
    if (!error) return this.props.children;
    const chunk = isChunkError(error);
    return (
      <div className={`empty-state error-state boundary-error ${this.props.page ? 'boundary-page' : ''}`} role="alert">
        <span>
          <Icon name="alert" />
        </span>
        <h3>{this.props.area}没能打开</h3>
        <p>{chunk ? '加载页面文件时网络中断了，或者织页刚更新过。其他区域不受影响，你的草稿也还在。' : '这一块出了点问题，其他区域不受影响。'}</p>
        <div className="boundary-actions">
          <button className="subtle-button" onClick={this.reset}>
            <Icon name="refresh" />
            重试
          </button>
          <button className="text-button" onClick={() => window.location.reload()}>
            刷新整个页面
          </button>
          {this.props.page ? (
            <a className="text-button" href="/create">
              回到创造
            </a>
          ) : null}
        </div>
      </div>
    );
  }
}
