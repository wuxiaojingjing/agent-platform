# ADR-009：出口缓存是否复用 OpenJiuwen 的 KVStore

状态：草案，待评审
日期：2026-07-28
相关：架构草案 v0.2 §4.3 第 4 行；ADR-003（网关往返记账）

## 决策

**不**用 `com.openjiuwen.extensions.store.kv.RedisStore` 承载 `DecisionCache`，
`gxz-cache-redis` 里手写的 Redisson 实现保留。

但 `KVStoreFactory.register` 是一个成立的复用点，方向与直觉相反——是把我们的实现
注册进 OJ，不是把我们的实现架在 OJ 上。**已落地**，见下文「反向复用」。

## 背景

`DecisionCache` 是意图引擎的一级出口缓存扩展点。本轮把 Redis 基线实现从
`gxz-fastpath` 抽到独立模块 `gxz-cache-redis`，以关掉 §4.3 第 4 行那条欠账
（引擎不该在编译期绑上 Redisson）。抽的时候应当先问：OJ 侧是否已有可复用的 KV 抽象。

答案是有：`com.openjiuwen.spi.store.BaseKVStore`（抽象基类）、`RedisStore`、
`DbBasedKVStore`、`InMemoryKVStore`，以及 `KVStoreFactory` / `KVStoreProvider` 两个 SPI。

## 验证方式

对 `agent-core-java:0.1.13` 反编译取签名，未跑起来实测：

```bash
javap -p -c -cp <解包目录> com.openjiuwen.extensions.store.kv.RedisStore
javap -cp <解包目录> com.openjiuwen.spi.store.BaseKVStore
```

结论基于字节码签名与 `set` 方法体，不是基于类名推测。下面每条都标了依据。

## 四条不能用的理由

**一、`set` 不带 TTL,而且写入永不过期。**
`BaseKVStore.set(String, Object)` 的签名里没有 TTL 参数。`RedisStore.set` 的方法体是
`aconst_null` 传给 `setInternal(String, Object, Integer)`——TTL 恒为 null。
缓存的核心语义就是过期,这一条单独就否掉了它作为缓存底座。

**二、带 TTL 的只有 `exclusiveSet`,而它是 SETNX 语义,不覆盖已有键。**
这条要说得准一点:它并非立刻致命。`DecisionCacheKey` 已含资产版本、embedding 模型版本、
指令模板版本,同版本内同一请求的键与结论都是确定的,重写是幂等的,所以 SETNX 在功能上
可接受。真正的问题是 TTL 从此由首个写入者定死,想续期得调 `refreshTtl`——而
`refreshTtl` 只在 `RedisStore` 上,不在 `BaseKVStore` 上。也就是说照抽象写、
却要靠具体子类才能续期,抽象带来的可替换性当场没了。

**三、它并不能让我们摆脱 Redisson。**
`RedisStore` 的构造器收的是 `java.lang.Object`,内部靠反射按方法名与参数个数打分匹配
（`tryInvoke` / `MethodMatch` / `ArgumentMatch`）。客户端仍得我们自己提供。
于是依赖一个没少,还在缓存热路径上多了一层反射。而抽掉 Redisson 正是本轮的目的。

**四、值是无类型 `Object`,序列化仍在我们这边。**
`get` 返回 `Object`,具体类型取决于被反射那个客户端的 codec。
`ArbitrationDecision` 的 JSON 编解码一样要自己写,省不掉。

还有一条风险值得单独记:反射按名字派发意味着它是**软失败**的。被反射的客户端改了方法名
不会编译报错,只会在运行期匹配不上——而那正是本工程一直在消除的那类「不报错的故障」。

## 公允地说

`RedisStore` 不是设计得差。它服务的是 OJ 自己的 session / memory store,那些场景以
永久写入为常态,不需要 TTL,而反射派发让 OJ 不必绑定某个具体 Redis 客户端。
错配只发生在「缓存」这个语义上。

## 反向复用（已落地）

`KVStoreFactory.register(String, KVStoreProvider)` + `KVStoreProvider.create(Map)`
允许把我们的 Redis 配置注册进 OJ,让 OJ 侧组件与意图引擎共用同一套连接配置,
而不是各配一份。这与 `gxz-oj-adapters` 已有的做法同构——那个模块已经用
`registerFactory(...)` 把模型网关与向量化注册进 OJ 的扩展点。

落点定在 `gxz-cache-redis`（`GxzRedisKVStoreProvider` + `OjKVStoreRegistration`），
不是 `gxz-oj-adapters`。理由:注册要拿到 `RedissonClient`,放进 oj-adapters 就得让它依赖
Redisson,而 `gxz-slowpath` 依赖 oj-adapters——Redisson 会凭传递依赖进到慢路径,
正是本轮刚清掉的那类污染。

`agent-core-java` 在本模块标 `<optional>true</optional>`(已确认落在发布出去的 pom 里),
optional 不传递,所以「只要 Redis 缓存、不要 OJ」的用法仍然成立;
`OjKVStoreRegistration` 由 `@ConditionalOnClass(KVStoreFactory.class)` 守着,
OJ 不在 classpath 上时整段装配安静地不生效,而不是抛 `NoClassDefFoundError`。

有意思的是,`RedisStore` 那套反射派发在这个方向上反而是好处:构造器收 `Object`,
所以 `RedissonClient` 直接传进去就行,一行适配代码都不用写。
同一个设计在缓存路径上是成本(热路径上多一层反射),在这里是收益。

`OjKVStoreRegistrationTest` 断言的是「provider 真注册进去且造得出实例」,
不是「上下文起得来」——因为这条线的失效方式是静默的:OJ 按名字找不到 `gxz` 会回落到
自带实现,功能全对、日志无异常,只有缓存与会话落在不同 Redis 实例上。
摘掉 `KVStoreFactory.register` 那两行,该用例确认变红。

## 过程教训

我是先建好 `gxz-cache-redis`、手写完 Redisson 实现,才去查 OJ 有没有现成的。
顺序反了。这次验证结果恰好支持了原本的选择,但那是运气,不是流程。

今后新建模块或新写基础设施实现前,先查 OJ 是否已覆盖,并把结论记下来——
**包括这次这样的否定结论**,好让下一个人不必重新反编译一遍 jar。
