<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { Check, ChevronDown } from '@lucide/vue'

export interface SelectOption {
  value: string
  label: string
  hint?: string
}

const props = withDefaults(
  defineProps<{
    modelValue: string
    options: readonly (SelectOption | string)[]
    placeholder?: string
    ariaLabel?: string
    disabled?: boolean
  }>(),
  {
    placeholder: '请选择',
    disabled: false,
  },
)

const emit = defineEmits<{ (e: 'update:modelValue', value: string): void }>()

const list = computed<SelectOption[]>(() =>
  props.options.map((o) => (typeof o === 'string' ? { value: o, label: o } : o)),
)
const current = computed(() => list.value.find((o) => o.value === props.modelValue) ?? null)

const open = ref(false)
const active = ref(0)
const btnRef = ref<HTMLButtonElement | null>(null)
const menuRef = ref<HTMLUListElement | null>(null)
const pos = ref({ left: 0, top: 0, width: 180 })

function place() {
  const r = btnRef.value?.getBoundingClientRect()
  if (!r) return
  const estH = Math.min(280, list.value.length * 38 + 12)
  const up = r.bottom + estH + 8 > window.innerHeight && r.top > estH + 8
  const width = Math.max(r.width, 160)
  pos.value = {
    left: Math.max(8, Math.min(r.left, window.innerWidth - width - 8)),
    top: up ? Math.max(8, r.top - estH - 6) : r.bottom + 6,
    width,
  }
}

function openMenu() {
  if (props.disabled) return
  place()
  active.value = Math.max(0, list.value.findIndex((o) => o.value === props.modelValue))
  open.value = true
}

function closeMenu(refocus = false) {
  open.value = false
  if (refocus) btnRef.value?.focus()
}

function choose(idx: number) {
  const opt = list.value[idx]
  if (opt) emit('update:modelValue', opt.value)
  closeMenu(true)
}

function onPointerDown(e: PointerEvent) {
  const t = e.target as Node
  if (!btnRef.value?.contains(t) && !menuRef.value?.contains(t)) closeMenu(false)
}

function onReflow() {
  place()
}

watch(open, (v) => {
  if (v) {
    document.addEventListener('pointerdown', onPointerDown, true)
    window.addEventListener('resize', onReflow)
    window.addEventListener('scroll', onReflow, true)
  } else {
    document.removeEventListener('pointerdown', onPointerDown, true)
    window.removeEventListener('resize', onReflow)
    window.removeEventListener('scroll', onReflow, true)
  }
})

onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', onPointerDown, true)
  window.removeEventListener('resize', onReflow)
  window.removeEventListener('scroll', onReflow, true)
})

function onKeyDown(e: KeyboardEvent) {
  if (props.disabled) return
  if (!open.value) {
    if (['ArrowDown', 'ArrowUp', 'Enter', ' '].includes(e.key)) {
      e.preventDefault()
      openMenu()
    }
    return
  }
  if (e.key === 'ArrowDown') {
    e.preventDefault()
    active.value = Math.min(list.value.length - 1, active.value + 1)
  } else if (e.key === 'ArrowUp') {
    e.preventDefault()
    active.value = Math.max(0, active.value - 1)
  } else if (e.key === 'Enter' || e.key === ' ') {
    e.preventDefault()
    choose(active.value)
  } else if (e.key === 'Escape' || e.key === 'Tab') {
    e.preventDefault()
    closeMenu(e.key === 'Escape')
  }
}
</script>

<template>
  <button
    ref="btnRef"
    type="button"
    role="combobox"
    :aria-expanded="open"
    aria-haspopup="listbox"
    :aria-label="ariaLabel"
    :disabled="disabled"
    class="inline-flex h-10 w-full items-center justify-between gap-2 rounded-lg border border-slate-200 bg-white px-3 text-left text-sm text-slate-800 shadow-2xs transition hover:border-slate-300 focus:border-indigo-500 focus:outline-none focus:ring-2 focus:ring-indigo-500/20 disabled:cursor-not-allowed disabled:bg-slate-50 disabled:text-slate-400"
    @click="open ? closeMenu(false) : openMenu()"
    @keydown="onKeyDown"
  >
    <span class="min-w-0 flex-1 truncate" :class="current ? 'text-slate-800' : 'text-slate-400'">
      {{ current ? current.label : placeholder }}
    </span>
    <ChevronDown class="h-4 w-4 shrink-0 text-slate-400 transition-transform" :class="open ? 'rotate-180 text-indigo-500' : ''" />
  </button>
  <Teleport to="body">
    <ul
      v-if="open"
      ref="menuRef"
      role="listbox"
      :aria-label="ariaLabel || placeholder"
      :style="{ position: 'fixed', left: `${pos.left}px`, top: `${pos.top}px`, width: `${pos.width}px`, zIndex: 9999 }"
      class="max-h-72 overflow-y-auto rounded-xl border border-slate-200 bg-white p-1.5 text-sm shadow-xl ring-1 ring-slate-900/5"
    >
      <li v-if="list.length === 0" class="px-3 py-2 text-center text-xs text-slate-400">暂无可选项</li>
      <li
        v-for="(opt, idx) in list"
        :key="opt.value"
        role="option"
        :aria-selected="opt.value === modelValue"
        class="flex cursor-pointer items-center justify-between gap-2 rounded-lg px-3 py-2 transition"
        :class="
          opt.value === modelValue
            ? 'bg-indigo-50 font-medium text-indigo-700'
            : idx === active
              ? 'bg-slate-100 text-slate-900'
              : 'text-slate-700 hover:bg-slate-50'
        "
        @mouseenter="active = idx"
        @mousedown.prevent="choose(idx)"
      >
        <span class="min-w-0 flex-1">
          <span class="block truncate">{{ opt.label }}</span>
          <span v-if="opt.hint" class="block truncate text-xs font-normal text-slate-400">{{ opt.hint }}</span>
        </span>
        <Check v-if="opt.value === modelValue" class="h-4 w-4 shrink-0 text-indigo-600" />
      </li>
    </ul>
  </Teleport>
</template>
