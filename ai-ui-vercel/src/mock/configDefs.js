/**
 * 配置定义 + 行数据 mock —— 从 ai-service DataSeedService.java 平移。
 * 前端 AI runtime 模式下,后端只代理对话;配置数据完全在浏览器内存中维护。
 *
 * 数据结构对齐后端 ConfigDefinition / ConfigDataRow:
 *   ConfigDefinition: { id, code, name, description, columns: [{key,label,type,required,options,defaultValue}], enabled }
 *   ConfigDataRow:     { id, configDefId, rowData: { fieldKey: value } }
 *
 * 行数据用确定性伪随机(种子 12345)生成,与后端 initDataRows 等价,保证可复现。
 */

// 简单确定性 PRNG(LCG),种子 12345,对齐后端 new Random(12345L) 的序列近似
function makeRng(seed = 12345) {
  let s = seed
  return () => {
    // Java Random: nextInt(bound) = (int)((bound * (long)next(31)) >> 31) ; next(bits) = seed = (seed * 0x5DEECE66D + 0xB) & ((1<<48)-1); return (int)(seed >>> (48 - bits))
    // 这里用 LCG 近似,数值不要求与后端逐字节一致,只需分布合理、可复现
    s = (s * 0x5DEECE66D + 0xB) & 0xffffffffffff
    return (s >>> 16) / 0x100000000
  }
}

function pick(rnd, arr) { return arr[Math.floor(rnd() * arr.length)] }

// ===================== 配置定义(4 个) =====================
export const CONFIG_DEFINITIONS = [
  {
    id: 1, code: 'CONFIG_A', name: '渠道销售配置', enabled: true,
    description: '管理各渠道的销售字段,包括销量、价格、销售员等',
    columns: [
      { key: 'a', label: '销售员姓名', type: 'string', required: true, options: [], defaultValue: '' },
      { key: 'b', label: '销量', type: 'number', required: true, options: [], defaultValue: 0 },
      { key: 'c', label: '价格等级', type: 'select', required: true, options: ['高', '中', '低'], defaultValue: '中' },
      { key: 'd', label: '销售日期', type: 'date', required: false, options: [], defaultValue: new Date().toISOString().slice(0, 10) },
      { key: 'e', label: '备注', type: 'string', required: false, options: [], defaultValue: '' }
    ]
  },
  {
    id: 2, code: 'CONFIG_B', name: '员工配置', enabled: true,
    description: '员工基础信息配置',
    columns: [
      { key: 'x', label: '工号', type: 'string', required: true, options: [], defaultValue: '' },
      { key: 'y', label: '姓名', type: 'string', required: true, options: [], defaultValue: '' },
      { key: 'z', label: '部门', type: 'select', required: true, options: ['研发部', '市场部', '销售部', '人力资源部', '财务部'], defaultValue: '研发部' },
      { key: 'w', label: '职级', type: 'select', required: false, options: ['P5', 'P6', 'P7', 'P8', 'P9'], defaultValue: 'P6' },
      { key: 'v', label: '入职日期', type: 'date', required: false, options: [], defaultValue: new Date().toISOString().slice(0, 10) }
    ]
  },
  {
    id: 3, code: 'CONFIG_C', name: '产品参数配置', enabled: true,
    description: '各类产品的技术参数',
    columns: [
      { key: 'sku', label: '产品编码', type: 'string', required: true, options: [], defaultValue: '' },
      { key: 'name', label: '产品名称', type: 'string', required: true, options: [], defaultValue: '' },
      { key: 'category', label: '产品类别', type: 'select', required: true, options: ['电子产品', '家居用品', '办公用品', '食品饮料'], defaultValue: '电子产品' },
      { key: 'weight', label: '重量(kg)', type: 'number', required: false, options: [], defaultValue: 0 },
      { key: 'price', label: '指导价', type: 'number', required: true, options: [], defaultValue: 0 },
      { key: 'enabled', label: '是否启用', type: 'boolean', required: false, options: [], defaultValue: true }
    ]
  },
  {
    id: 4, code: 'CONFIG_D', name: '订单规则配置', enabled: true,
    description: '订单匹配与处理规则配置',
    columns: [
      { key: 'ruleCode', label: '规则编码', type: 'string', required: true, options: [], defaultValue: '' },
      { key: 'ruleName', label: '规则名称', type: 'string', required: true, options: [], defaultValue: '' },
      { key: 'minAmount', label: '最小金额', type: 'number', required: true, options: [], defaultValue: 0 },
      { key: 'discount', label: '折扣率', type: 'number', required: false, options: [], defaultValue: 1 },
      { key: 'priority', label: '优先级', type: 'number', required: false, options: [], defaultValue: 100 },
      { key: 'status', label: '状态', type: 'select', required: true, options: ['启用', '停用'], defaultValue: '启用' }
    ]
  }
]

// ===================== 行数据生成 =====================
function genRows() {
  const rnd = makeRng(12345)
  const rows = {} // defId -> [{id, configDefId, rowData}]

  // CONFIG_A: 50 行
  const namesA = ['张三', '李四', '王五', '赵六', '钱七', '孙八', '周九', '吴十', '郑十一', '冯十二']
  const levels = ['高', '中', '低']
  rows[1] = []
  for (let i = 0; i < 50; i++) {
    rows[1].push({
      id: i + 1, configDefId: 1,
      rowData: {
        a: pick(rnd, namesA),
        b: Math.floor(rnd() * 1000) + 1,
        c: pick(rnd, levels),
        d: daysAgo(rnd, 365),
        e: i % 3 === 0 ? '大客户订单' : ''
      }
    })
  }

  // CONFIG_B: 30 行
  const namesB = ['陈A', '林B', '黄C', '刘D', '杨E', '朱F', '徐G', '马H', '胡I', '郭J']
  const depts = ['研发部', '市场部', '销售部', '人力资源部', '财务部']
  const ranks = ['P5', 'P6', 'P7', 'P8', 'P9']
  rows[2] = []
  for (let i = 0; i < 30; i++) {
    rows[2].push({
      id: i + 1, configDefId: 2,
      rowData: {
        x: 'E' + String(10000 + i).padStart(5, '0'),
        y: pick(rnd, namesB) + (i + 1),
        z: pick(rnd, depts),
        w: pick(rnd, ranks),
        v: daysAgo(rnd, 365 * 8)
      }
    })
  }

  // CONFIG_C: 100 行
  const cats = ['电子产品', '家居用品', '办公用品', '食品饮料']
  const prefix = ['SKU-PC', 'SKU-HOME', 'SKU-OF', 'SKU-FD']
  const prodNames = ['笔记本电脑', '手机', '耳机', '台灯', '椅子', '办公桌', '矿泉水', '饼干', '方便面', '显示器', '键盘', '鼠标']
  rows[3] = []
  for (let i = 0; i < 100; i++) {
    const ci = Math.floor(rnd() * cats.length)
    rows[3].push({
      id: i + 1, configDefId: 3,
      rowData: {
        sku: prefix[ci] + '-' + String(i).padStart(4, '0'),
        name: pick(rnd, prodNames) + ' ' + (i + 1) + '号',
        category: cats[ci],
        weight: Math.round(rnd() * 50 * 100) / 100,
        price: Math.round((rnd() * 5000 + 10) * 100) / 100,
        enabled: Math.floor(rnd() * 5) !== 0
      }
    })
  }

  // CONFIG_D: 20 行
  const minAmounts = [0, 100, 500, 1000, 3000, 5000, 10000]
  rows[4] = []
  for (let i = 1; i <= 20; i++) {
    rows[4].push({
      id: i, configDefId: 4,
      rowData: {
        ruleCode: 'R' + String(i).padStart(3, '0'),
        ruleName: '订单规则-' + i,
        minAmount: pick(rnd, minAmounts),
        discount: Math.round((0.5 + rnd() * 0.5) * 100) / 100,
        priority: Math.floor(rnd() * 200),
        status: Math.floor(rnd() * 6) === 0 ? '停用' : '启用'
      }
    })
  }
  return rows
}

function daysAgo(rnd, span) {
  const d = new Date()
  d.setDate(d.getDate() - Math.floor(rnd() * span))
  return d.toISOString().slice(0, 10)
}

/** 初始化一份行数据(深拷贝,避免被修改污染源) */
export function createInitialRows() {
  const src = genRows()
  const out = {}
  for (const k of Object.keys(src)) {
    out[k] = src[k].map(r => ({ ...r, rowData: { ...r.rowData } }))
  }
  return out
}
