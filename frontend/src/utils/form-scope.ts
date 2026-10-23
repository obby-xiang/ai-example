/**
 * 生成式表单卡的**卡作用域**（L-92）。
 *
 * 现场：同一页面里可以同时有「上一轮已决的只读回放卡」与「本轮待填卡」，两卡 schema 同形 ⇒
 * 字段 DOM id 必然撞车（`label for` / `aria-describedby` / 首错聚焦都是按 id 取第一个命中）。
 *
 * 口径：
 * - **交互卡**（本轮挂起）的作用域 = `toolCallId`（上传归属已带同一值，不需要新增宿主入参）；
 * - **只读回放卡** 没有挂起归属 ⇒ 走这里发的实例序号。序号在**模块级**自增（`<script setup>` 里
 *   的顶层声明是每实例执行一次，做不了这件事），因此同页任意多卡、含多 app 实例都互不相同；
 *   只读面非交互、不参与定位，故不要求可预测。
 */

let sequence = 0

/** 下一个只读回放卡的作用域（单调递增，进程内唯一）。 */
export function nextReadonlyFormScope(): string {
  sequence += 1
  return `ro${sequence}`
}
