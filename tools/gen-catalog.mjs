// 生成模组内置的商品目录（首次启动会复制到 config/aimcmarket/catalog.json）。
// 用法：node tools/gen-catalog.mjs > src/main/resources/aimcmarket/catalog.json
//
// 字段：id / name_zh / aliases / basePrice（分）/ volatility / liquidity / enabled
// 价格是"分"：1200 = 12.00 币。liquidity 也按分算，越大越抗冲击。

const rows = [
  // 矿物与基础材料
  ['minecraft:iron_ingot', '铁锭', '铁,iron,铁锭', 1200, 0.020, 12000],
  ['minecraft:gold_ingot', '金锭', '金,gold,金子', 4000, 0.025, 9000],
  ['minecraft:copper_ingot', '铜锭', '铜,copper', 900, 0.022, 10000],
  ['minecraft:diamond', '钻石', '钻,diamond,钻石', 20000, 0.035, 6000],
  ['minecraft:emerald', '绿宝石', '绿宝石,emerald', 6000, 0.030, 7000],
  ['minecraft:coal', '煤炭', '煤,coal,煤炭', 300, 0.025, 20000],
  ['minecraft:charcoal', '木炭', '碳,charcoal,木炭', 200, 0.025, 16000],
  ['minecraft:raw_iron', '粗铁', '铁矿石,raw iron,粗铁', 800, 0.025, 14000],
  ['minecraft:raw_gold', '粗金', '金矿石,raw gold,粗金', 3000, 0.030, 9000],
  ['minecraft:raw_copper', '粗铜', '铜矿石,raw copper,粗铜', 600, 0.025, 14000],
  ['minecraft:redstone', '红石', '红石,redstone', 400, 0.030, 15000],
  ['minecraft:lapis_lazuli', '青金石', '青金石,lapis', 800, 0.030, 10000],
  ['minecraft:quartz', '下界石英', '石英,quartz', 500, 0.028, 10000],
  ['minecraft:amethyst_shard', '紫水晶碎片', '紫水晶,amethyst', 1200, 0.035, 6000],
  ['minecraft:netherite_scrap', '远古残骸碎片', '残骸,下界合金碎片,netherite scrap', 15000, 0.040, 3000],
  ['minecraft:netherite_ingot', '下界合金锭', '下界合金,netherite', 90000, 0.045, 2000],
  ['minecraft:emerald_block', '绿宝石块', '绿宝石块,emerald block', 54000, 0.030, 3000],
  ['minecraft:diamond_block', '钻石块', '钻石块,diamond block', 180000, 0.035, 2000],
  ['minecraft:iron_block', '铁块', '铁块,iron block', 10800, 0.020, 5000],
  ['minecraft:gold_block', '金块', '金块,gold block', 36000, 0.025, 4000],
  ['minecraft:lapis_block', '青金石块', '青金石块,lapis block', 7200, 0.030, 4000],
  ['minecraft:redstone_block', '红石块', '红石块,redstone block', 3600, 0.030, 5000],
  ['minecraft:coal_block', '煤炭块', '煤炭块,coal block', 2700, 0.025, 5000],
  ['minecraft:quartz_block', '石英块', '石英块,quartz block', 1600, 0.028, 5000],
  ['minecraft:amethyst_block', '紫水晶块', '紫水晶块,amethyst block', 4000, 0.035, 3000],
  // 木头与建材
  ['minecraft:oak_log', '橡木原木', '橡木,原木,oak log,木头', 150, 0.020, 22000],
  ['minecraft:spruce_log', '云杉原木', '云杉,spruce log', 150, 0.020, 20000],
  ['minecraft:birch_log', '白桦原木', '白桦,birch log', 160, 0.020, 20000],
  ['minecraft:jungle_log', '丛林木原木', '丛林,jungle log', 180, 0.022, 16000],
  ['minecraft:acacia_log', '金合欢原木', '金合欢,acacia log', 170, 0.022, 16000],
  ['minecraft:dark_oak_log', '深色橡木原木', '深色橡木,dark oak log', 190, 0.022, 15000],
  ['minecraft:oak_planks', '橡木木板', '木板,planks,橡木板', 80, 0.018, 24000],
  ['minecraft:stone', '石头', '石头,stone', 60, 0.015, 30000],
  ['minecraft:cobblestone', '圆石', '圆石,鹅卵石,cobblestone', 40, 0.015, 32000],
  ['minecraft:sand', '沙子', '沙子,sand', 60, 0.018, 26000],
  ['minecraft:gravel', '沙砾', '沙砾,gravel', 50, 0.018, 24000],
  ['minecraft:glass', '玻璃', '玻璃,glass', 300, 0.020, 14000],
  ['minecraft:obsidian', '黑曜石', '黑曜石,obsidian', 1000, 0.025, 8000],
  ['minecraft:netherrack', '下界岩', '下界岩,netherrack', 80, 0.020, 20000],
  ['minecraft:soul_sand', '灵魂沙', '灵魂沙,soul sand', 300, 0.022, 12000],
  ['minecraft:glowstone', '荧石', '荧石,glowstone', 700, 0.028, 10000],
  ['minecraft:end_stone', '末地石', '末地石,end stone', 200, 0.022, 14000],
  ['minecraft:dirt', '泥土', '泥土,dirt', 20, 0.015, 30000],
  ['minecraft:clay_ball', '黏土球', '黏土,clay', 100, 0.020, 16000],
  ['minecraft:brick', '红砖', '红砖,brick', 150, 0.020, 16000],
  ['minecraft:torch', '火把', '火把,torch', 40, 0.018, 26000],
  ['minecraft:chest', '箱子', '箱子,chest', 500, 0.020, 14000],
  ['minecraft:barrel', '木桶', '木桶,barrel', 600, 0.020, 12000],
  ['minecraft:furnace', '熔炉', '熔炉,furnace', 300, 0.020, 16000],
  ['minecraft:hopper', '漏斗', '漏斗,hopper', 2500, 0.025, 8000],
  ['minecraft:crafting_table', '工作台', '工作台,crafting table', 200, 0.018, 18000],
  ['minecraft:bookshelf', '书架', '书架,bookshelf', 900, 0.022, 10000],
  ['minecraft:scaffolding', '脚手架', '脚手架,scaffolding', 300, 0.022, 12000],
  ['minecraft:campfire', '营火', '营火,campfire', 700, 0.022, 10000],
  ['minecraft:lantern', '灯笼', '灯笼,lantern', 600, 0.022, 10000],
  ['minecraft:redstone_torch', '红石火把', '红石火把,redstone torch', 100, 0.020, 16000],
  ['minecraft:rail', '铁轨', '铁轨,rail', 500, 0.022, 12000],
  ['minecraft:minecart', '矿车', '矿车,minecart', 1500, 0.025, 8000],
  ['minecraft:tnt', 'TNT', 'tnt,炸药', 2000, 0.030, 7000],
  // 食物
  ['minecraft:apple', '苹果', '苹果,apple', 300, 0.020, 16000],
  ['minecraft:bread', '面包', '面包,bread', 500, 0.020, 14000],
  ['minecraft:wheat', '小麦', '小麦,wheat', 200, 0.020, 20000],
  ['minecraft:wheat_seeds', '小麦种子', '种子,seeds', 20, 0.020, 24000],
  ['minecraft:carrot', '胡萝卜', '胡萝卜,carrot', 150, 0.020, 20000],
  ['minecraft:potato', '马铃薯', '土豆,potato', 150, 0.020, 20000],
  ['minecraft:baked_potato', '烤马铃薯', '烤土豆,baked potato', 250, 0.020, 16000],
  ['minecraft:beetroot', '甜菜根', '甜菜,beetroot', 150, 0.020, 16000],
  ['minecraft:sugar', '糖', '糖,sugar', 150, 0.020, 18000],
  ['minecraft:sugar_cane', '甘蔗', '甘蔗,sugar cane', 100, 0.020, 20000],
  ['minecraft:melon', '西瓜', '西瓜,melon', 200, 0.020, 16000],
  ['minecraft:pumpkin', '南瓜', '南瓜,pumpkin', 250, 0.020, 16000],
  ['minecraft:porkchop', '生猪排', '猪排,猪肉,porkchop', 350, 0.022, 14000],
  ['minecraft:cooked_porkchop', '熟猪排', '熟猪肉,cooked porkchop', 600, 0.022, 12000],
  ['minecraft:beef', '生牛肉', '牛肉,beef', 400, 0.022, 14000],
  ['minecraft:cooked_beef', '牛排', '牛排,cooked beef', 700, 0.022, 12000],
  ['minecraft:chicken', '生鸡肉', '鸡肉,chicken', 300, 0.022, 14000],
  ['minecraft:cooked_chicken', '熟鸡肉', '熟鸡肉,cooked chicken', 600, 0.022, 12000],
  ['minecraft:mutton', '生羊肉', '羊肉,mutton', 400, 0.022, 12000],
  ['minecraft:cooked_mutton', '熟羊肉', '熟羊肉,cooked mutton', 650, 0.022, 10000],
  ['minecraft:cod', '生鳕鱼', '鳕鱼,cod', 300, 0.022, 14000],
  ['minecraft:cooked_cod', '熟鳕鱼', '熟鳕鱼,cooked cod', 500, 0.022, 12000],
  ['minecraft:salmon', '生鲑鱼', '鲑鱼,三文鱼,salmon', 350, 0.022, 14000],
  ['minecraft:cooked_salmon', '熟鲑鱼', '熟三文鱼,cooked salmon', 550, 0.022, 12000],
  ['minecraft:egg', '鸡蛋', '鸡蛋,egg', 100, 0.020, 18000],
  ['minecraft:golden_apple', '金苹果', '金苹果,golden apple', 3000, 0.035, 5000],
  ['minecraft:golden_carrot', '金胡萝卜', '金胡萝卜,golden carrot', 1500, 0.030, 6000],
  ['minecraft:cake', '蛋糕', '蛋糕,cake', 800, 0.022, 9000],
  ['minecraft:honeycomb', '蜜脾', '蜜脾,honeycomb', 600, 0.022, 10000],
  // 掉落物/杂项
  ['minecraft:feather', '羽毛', '羽毛,feather', 100, 0.022, 18000],
  ['minecraft:leather', '皮革', '皮革,leather', 300, 0.022, 16000],
  ['minecraft:string', '线', '线,string', 120, 0.022, 18000],
  ['minecraft:bone', '骨头', '骨头,bone', 150, 0.022, 18000],
  ['minecraft:gunpowder', '火药', '火药,gunpowder', 250, 0.025, 16000],
  ['minecraft:flint', '燧石', '燧石,flint', 180, 0.022, 16000],
  ['minecraft:slime_ball', '黏液球', '黏液球,slimeball', 250, 0.025, 14000],
  ['minecraft:slime_block', '黏液块', '黏液块,slime block', 1000, 0.025, 9000],
  ['minecraft:spider_eye', '蜘蛛眼', '蜘蛛眼,spider eye', 300, 0.025, 14000],
  ['minecraft:rotten_flesh', '腐肉', '腐肉,rotten flesh', 80, 0.020, 24000],
  ['minecraft:ender_pearl', '末影珍珠', '末影珍珠,ender pearl', 1500, 0.030, 9000],
  ['minecraft:blaze_rod', '烈焰棒', '烈焰棒,blaze rod', 2500, 0.032, 7000],
  ['minecraft:ghast_tear', '恶魂之泪', '恶魂之泪,ghast tear', 3000, 0.035, 5000],
  ['minecraft:magma_cream', '岩浆膏', '岩浆膏,magma cream', 2000, 0.032, 6000],
  ['minecraft:ender_eye', '末影之眼', '末影之眼,ender eye', 4000, 0.035, 4000],
  ['minecraft:shulker_shell', '潜影壳', '潜影壳,shulker shell', 20000, 0.040, 2500],
  ['minecraft:name_tag', '命名牌', '命名牌,name tag', 2000, 0.032, 5000],
  ['minecraft:saddle', '鞍', '鞍,saddle', 3000, 0.030, 5000],
  ['minecraft:lead', '拴绳', '拴绳,lead', 600, 0.022, 10000],
  ['minecraft:compass', '指南针', '指南针,compass', 1500, 0.025, 9000],
  ['minecraft:clock', '钟', '时钟,clock', 1500, 0.025, 9000],
  ['minecraft:map', '地图', '地图,map', 1000, 0.022, 10000],
  ['minecraft:book', '书', '书,book', 400, 0.020, 14000],
  ['minecraft:paper', '纸', '纸,paper', 200, 0.020, 18000],
  ['minecraft:shears', '剪刀', '剪刀,shears', 800, 0.022, 10000],
  ['minecraft:fishing_rod', '钓鱼竿', '钓鱼竿,fishing rod', 900, 0.022, 10000],
  ['minecraft:firework_rocket', '烟花火箭', '烟花,firework', 300, 0.028, 12000],
  ['minecraft:shield', '盾牌', '盾牌,shield', 2000, 0.025, 8000],
  ['minecraft:bow', '弓', '弓,bow', 1200, 0.025, 10000],
  ['minecraft:arrow', '箭', '箭,arrow', 100, 0.020, 20000],
  ['minecraft:iron_pickaxe', '铁镐', '铁镐,iron pickaxe', 2500, 0.025, 8000],
  ['minecraft:iron_sword', '铁剑', '铁剑,iron sword', 2000, 0.025, 9000],
  ['minecraft:iron_helmet', '铁头盔', '铁头盔,iron helmet', 3000, 0.025, 7000],
  ['minecraft:iron_chestplate', '铁胸甲', '铁胸甲,iron chestplate', 4500, 0.025, 6000],
  ['minecraft:iron_leggings', '铁护腿', '铁护腿,iron leggings', 4000, 0.025, 6000],
  ['minecraft:iron_boots', '铁靴子', '铁靴子,iron boots', 2500, 0.025, 7000],
  ['minecraft:diamond_sword', '钻石剑', '钻石剑,diamond sword', 6000, 0.030, 5000],
  ['minecraft:diamond_pickaxe', '钻石镐', '钻石镐,diamond pickaxe', 7000, 0.030, 5000],
  ['minecraft:diamond_helmet', '钻石头盔', '钻石头盔,diamond helmet', 15000, 0.030, 4000],
  ['minecraft:diamond_chestplate', '钻石胸甲', '钻石胸甲,diamond chestplate', 20000, 0.032, 3500],
  ['minecraft:diamond_leggings', '钻石护腿', '钻石护腿,diamond leggings', 18000, 0.032, 3500],
  ['minecraft:diamond_boots', '钻石靴子', '钻石靴子,diamond boots', 14000, 0.030, 4000],
  ['minecraft:enchanting_table', '附魔台', '附魔台,enchanting table', 8000, 0.030, 4000],
  ['minecraft:anvil', '铁砧', '铁砧,anvil', 9000, 0.028, 4000],
  ['minecraft:beacon', '信标', '信标,beacon', 50000, 0.040, 1500],
  ['minecraft:conduit', '潮涌核心', '潮涌核心,conduit', 30000, 0.038, 1800],
  ['minecraft:ender_chest', '末影箱', '末影箱,ender chest', 12000, 0.032, 3000],
];

const items = rows.map((row) => {
  const [id, zh, aliases, basePrice, volatility, liquidity] = row;
  return {
    id,
    name_zh: zh,
    aliases: String(aliases).split(',').map((s) => s.trim()).filter(Boolean),
    basePrice,
    volatility,
    liquidity,
    enabled: true,
  };
});

if (items.length < 90) {
  throw new Error(`商品目录太少了：${items.length}`);
}

const doc = {
  schema: 1,
  note: 'AIMCmarket 商品目录。basePrice 单位是分（1200 = 12.00 币）；改完 /ex admin reload 生效。',
  items,
};

// 可以传输出路径（推荐，避免 PowerShell 管道的编码坑）；不传就打到 stdout。
const outPath = process.argv[2];
if (outPath) {
  const { writeFileSync } = await import('node:fs');
  writeFileSync(outPath, JSON.stringify(doc, null, 2) + '\n', 'utf8');
  console.error(`已写入 ${outPath}（${items.length} 项）`);
} else {
  process.stdout.write(JSON.stringify(doc, null, 2) + '\n');
}

