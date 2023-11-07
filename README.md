# LsComment
### 介绍
基于Spring Boot + Redis的店铺点评APP，实现了查找店铺、优惠券秒杀、发表评论、点赞关注的完整业务流程。

### 主要功能
1. 查看热门点评、发布点评、给喜欢的帖子点赞<br/>
2. 关注其他用户、查询关注的人发布的帖子、查看和关注的人之间共同关注的人。
3. 统计用户签到的天数、分类浏览店铺、搜索附近的店铺<br/>
4. 秒杀购买优惠券<br/>
5. 个人信息查看和管理<br/>

### 技术栈
Spring相关:
- Spring Boot 2.x. 
- Spring MVC

数据存储层:
- MySQL:存储数据
- MyBatis Plus:数据访问框架

Redis相关:
- spring-data-redis:操作Redis
- Redisson:基于Redis的分布式锁

工具库:
- HuTool:工具库合集
- Lombok:注解式代码生成工具   
