import { useEffect, useId, useLayoutEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { Check, ChevronDown } from 'lucide-react'

export interface SelectOption<T extends string = string> {
  value: T
  label: string
  hint?: string
}

export interface SelectProps<T extends string = string> {
  value: T
  options: readonly (SelectOption<T> | T)[]
  onChange: (value: T) => void
  placeholder?: string
  ariaLabel?: string
  id?: string
  disabled?: boolean
  className?: string
}

function normalize<T extends string>(items: readonly (SelectOption<T> | T)[]): SelectOption<T>[] {
  return items.map((item) => (typeof item === 'string' ? { value: item as T, label: item } : item))
}

/** 统一自定义下拉选择框：避免 macOS/Windows 原生 <select> 深灰弹窗与页面风格割裂。 */
export function Select<T extends string = string>({
  value,
  options,
  onChange,
  placeholder = '请选择',
  ariaLabel,
  id,
  disabled = false,
  className = '',
}: SelectProps<T>) {
  const list = normalize(options)
  const [open, setOpen] = useState(false)
  const [active, setActive] = useState(0)
  const [pos, setPos] = useState({ left: 0, top: 0, width: 180 })
  const btnRef = useRef<HTMLButtonElement>(null)
  const menuRef = useRef<HTMLUListElement>(null)
  const listId = useId()

  const selectedIndex = list.findIndex((o) => o.value === value)
  const current = selectedIndex >= 0 ? list[selectedIndex] : null

  const place = () => {
    const r = btnRef.current?.getBoundingClientRect()
    if (!r) return
    const estH = Math.min(280, list.length * 38 + 12)
    const up = r.bottom + estH + 8 > window.innerHeight && r.top > estH + 8
    const width = Math.max(r.width, 160)
    setPos({
      left: Math.max(8, Math.min(r.left, window.innerWidth - width - 8)),
      top: up ? Math.max(8, r.top - estH - 6) : r.bottom + 6,
      width,
    })
  }

  const openMenu = () => {
    if (disabled) return
    place()
    setActive(Math.max(0, selectedIndex))
    setOpen(true)
  }

  const closeMenu = (refocus = false) => {
    setOpen(false)
    if (refocus) btnRef.current?.focus()
  }

  const choose = (index: number) => {
    const opt = list[index]
    if (opt) onChange(opt.value)
    closeMenu(true)
  }

  useLayoutEffect(() => {
    if (!open) return
    menuRef.current?.querySelector<HTMLElement>(`[data-idx="${active}"]`)?.scrollIntoView({ block: 'nearest' })
  }, [open, active])

  useEffect(() => {
    if (!open) return
    const onDown = (e: PointerEvent) => {
      const target = e.target as Node
      if (!btnRef.current?.contains(target) && !menuRef.current?.contains(target)) closeMenu(false)
    }
    const onReflow = () => place()
    document.addEventListener('pointerdown', onDown, true)
    window.addEventListener('resize', onReflow)
    window.addEventListener('scroll', onReflow, true)
    return () => {
      document.removeEventListener('pointerdown', onDown, true)
      window.removeEventListener('resize', onReflow)
      window.removeEventListener('scroll', onReflow, true)
    }
  }, [open, list.length])

  const onKeyDown = (e: React.KeyboardEvent) => {
    if (disabled) return
    if (!open) {
      if (e.key === 'ArrowDown' || e.key === 'ArrowUp' || e.key === 'Enter' || e.key === ' ') {
        e.preventDefault()
        openMenu()
      }
      return
    }
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      setActive((i) => Math.min(list.length - 1, i + 1))
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      setActive((i) => Math.max(0, i - 1))
    } else if (e.key === 'Home') {
      e.preventDefault()
      setActive(0)
    } else if (e.key === 'End') {
      e.preventDefault()
      setActive(Math.max(0, list.length - 1))
    } else if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault()
      choose(active)
    } else if (e.key === 'Escape' || e.key === 'Tab') {
      e.preventDefault()
      closeMenu(e.key === 'Escape')
    }
  }

  return (
    <>
      <button
        ref={btnRef}
        id={id}
        type="button"
        role="combobox"
        aria-expanded={open}
        aria-haspopup="listbox"
        aria-controls={open ? listId : undefined}
        aria-label={ariaLabel}
        disabled={disabled}
        onClick={() => (open ? closeMenu(false) : openMenu())}
        onKeyDown={onKeyDown}
        className={`inline-flex h-10 w-full items-center justify-between gap-2 rounded-lg border border-slate-200 bg-white px-3 text-left text-sm text-slate-800 shadow-2xs transition hover:border-slate-300 focus:border-indigo-500 focus:outline-none focus:ring-2 focus:ring-indigo-500/20 disabled:cursor-not-allowed disabled:bg-slate-50 disabled:text-slate-400 ${className}`.trim()}
      >
        <span className={`min-w-0 flex-1 truncate ${current ? 'text-slate-800' : 'text-slate-400'}`}>
          {current ? current.label : placeholder}
        </span>
        <ChevronDown className={`h-4 w-4 shrink-0 text-slate-400 transition-transform ${open ? 'rotate-180 text-indigo-500' : ''}`} />
      </button>
      {open
        ? createPortal(
            <ul
              ref={menuRef}
              id={listId}
              role="listbox"
              aria-label={ariaLabel || placeholder}
              style={{ position: 'fixed', left: pos.left, top: pos.top, width: pos.width, zIndex: 9999 }}
              className="max-h-72 overflow-y-auto rounded-xl border border-slate-200 bg-white p-1.5 text-sm shadow-xl ring-1 ring-slate-900/5"
            >
              {list.length === 0 ? (
                <li className="px-3 py-2 text-center text-xs text-slate-400">暂无可选项</li>
              ) : (
                list.map((opt, idx) => {
                  const selected = opt.value === value
                  const focused = idx === active
                  return (
                    <li
                      key={opt.value}
                      data-idx={idx}
                      role="option"
                      aria-selected={selected}
                      onMouseEnter={() => setActive(idx)}
                      onMouseDown={(e) => {
                        e.preventDefault()
                        choose(idx)
                      }}
                      className={`flex cursor-pointer items-center justify-between gap-2 rounded-lg px-3 py-2 transition ${
                        selected
                          ? 'bg-indigo-50 font-medium text-indigo-700'
                          : focused
                            ? 'bg-slate-100 text-slate-900'
                            : 'text-slate-700 hover:bg-slate-50'
                      }`}
                    >
                      <span className="min-w-0 flex-1">
                        <span className="block truncate">{opt.label}</span>
                        {opt.hint ? <span className="block truncate text-xs font-normal text-slate-400">{opt.hint}</span> : null}
                      </span>
                      {selected ? <Check className="h-4 w-4 shrink-0 text-indigo-600" /> : null}
                    </li>
                  )
                })
              )}
            </ul>,
            document.body,
          )
        : null}
    </>
  )
}

export default Select
