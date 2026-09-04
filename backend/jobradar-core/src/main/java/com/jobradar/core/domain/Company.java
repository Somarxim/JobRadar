package com.jobradar.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * 公司（companies 表）。
 *
 * <p>通用映射约定（适用全部实体，面试点集中在此类讲解）：
 * <ul>
 *   <li>Lombok 只用 {@code @Getter/@Setter}，刻意不用 {@code @Data}：
 *       @Data 生成的 equals/hashCode/toString 会遍历全部字段，JPA 实体的懒加载关联
 *       一旦被触发就是性能坑；实体要进 Set/Map 时应按 id 手写 equals/hashCode。</li>
 *   <li>主键 {@code GenerationType.IDENTITY}：对应 PG 的 GENERATED ALWAYS AS IDENTITY，
 *       由数据库自增。代价是 INSERT 后才能拿到 id（JPA 批量插入优化会失效），
 *       本项目单用户低写入量，换来最简单直观的模型。</li>
 *   <li>时间戳用应用侧的 {@code @CreationTimestamp}（Hibernate 扩展注解）而非依赖
 *       DB 的 DEFAULT now()：实体 new 出来即可读时间，单元测试不依赖数据库时钟；
 *       updated_at 在 DB 侧没有触发器能自动维护，更必须应用侧写。</li>
 *   <li>字段默认值（如 tier=NONE）在 Java 字段初始化器里与 DB DEFAULT 保持一致，
 *       因为 INSERT 总会带上显式值，DB 默认值实际不会生效——保持一致防漂移。</li>
 * </ul>
 */
@Entity
@Table(name = "companies")
@Getter
@Setter
public class Company {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    // 枚举入库经 LowercaseEnumConverter 转小写，匹配 CHECK 约束
    @Column(nullable = false)
    private CompanyType companyType = CompanyType.OTHER;

    @Column(nullable = false)
    private CompanyTier tier = CompanyTier.NONE;

    private String homepage;

    private String notes;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}
