# Delivery Status

权益SKU版本受理切片已完成实现、受影响测试和独立终审。分支feat/referral-sku-version；独立本地提交记录以git log为准；无推送、部署或共享迁移。

## 行为

新增可选expectedSkuVersion，严格JSON整数/NULL，旧Java构造、NULL wire省略和原hash字节保持兼容。指定版本时在原受理事务按SKU排序FOR SHARE读取当前主库，验证版本/ACTIVE/时间，库存与Outbox写入后再次检查原始时间。相同原成功在目录切版/停用后永久回放；改或删除expected版本造成幂等冲突。MyBatis XML和Spring共用原数据源事务，无新DDL。

## 验证与独立审查

14纯+4旧HTTP见pre-mysql-18；真实MySQL7见mysql-final/result.json及JUnit XML。process27752退出0，7/0失败/0错误/0跳过，20当前源文件与被测快照SHA一致。合计25项受影响专项，非平台全量。数据库覆盖版本0、旧请求、真实陈旧缓存、永久回放、并发切版等待、FOR SHARE持续到Spring提交，以及库存锁等待跨SKU到期后的完整原子回滚。独立reviewer核对源码、25项原XML与20 SHA，终审无阻断；无需重复已通过验证。

## 环境与保留门禁

此前旧Testcontainers API不兼容、Docker全量ListImages挂起及容器慢启动均保留失败日志。测试依赖升级2.0.3，test-only SingleImagePullPolicy只inspect指定镜像、NotFound才pull，其他错误传播；独立复核确认没有跳过真实验证或改变生产行为。仅终止自有卡住测试JVM，没有重启共享Docker、删除用户数据或清理现有服务。隔离容器14项既有迁移成功，不构成共享库迁移。

快照见snapshot-path.txt；临时offline-settings只匹配现有缓存repository ID，不改用户配置、不联网。真实SKU目录权威映射、渠道、外部合同、完整裂变奖励链路与生产验收仍有独立门禁。原平台绑定DB已接替重型数据库窗口。
