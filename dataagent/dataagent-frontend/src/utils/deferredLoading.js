import { onScopeDispose, ref, watch } from 'vue'

/**
 * 把一个 loading 标志变成「只有慢到值得提示时才为真」的标志。
 *
 * 设置页的首屏骨架屏原来是 loading 一为真就渲染。接口现在 6~16 ms 就返回，
 * 骨架屏只存在一帧——实测 `119-119ms SKELETON`，用户看到的是一次闪烁，
 * 而不是加载提示。
 *
 * 加一个下限延迟：快的时候完全不出现（无闪烁），慢的时候（生产环境 MySQL 在
 * 远端、skill 数量更多）才出现。这是这类问题的标准做法——不用最短展示时长，
 * 因为那会故意拖慢已经很快的页面。
 *
 * @param {import('vue').Ref<boolean>} source 真实的 loading 标志
 * @param {number} delay 超过这个毫秒数仍在加载才提示
 * @returns {import('vue').Ref<boolean>}
 */
export const useDeferredLoading = (source, delay = 200) => {
  const deferred = ref(false)
  let timer = null

  const clear = () => {
    if (timer !== null) {
      clearTimeout(timer)
      timer = null
    }
  }

  watch(
    source,
    (loading) => {
      clear()
      if (!loading) {
        deferred.value = false
        return
      }
      timer = setTimeout(() => {
        timer = null
        // 重新读一次：定时器排队期间可能已经加载完了。
        if (source.value) deferred.value = true
      }, delay)
    },
    { immediate: true }
  )

  // 组件卸载时别留下会写已销毁状态的定时器。
  onScopeDispose(clear)

  return deferred
}
