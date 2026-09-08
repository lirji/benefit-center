package com.lrj.benefit.adapters.config;

import com.lrj.benefit.adapters.persistence.MybatisSkuAcceptanceRepository;
import com.lrj.benefit.adapters.persistence.SkuAcceptanceMapper;
import com.lrj.benefit.application.port.out.SkuAcceptanceRepository;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.LocalCacheScope;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import javax.sql.DataSource;

/** 最小XML Mapper装配；共用现有DataSource，不另建事务或全局替换旧JDBC配置。 */
@Configuration
public class SkuAcceptanceConfiguration {
    /** 显式关闭会话缓存，让重复锁定读取仍访问数据库当前事实。 */
    @Bean SqlSessionFactory skuAcceptanceSqlSessionFactory(DataSource dataSource) throws Exception {
        var configuration = new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setArgNameBasedConstructorAutoMapping(true);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        factory.setMapperLocations(new ClassPathResource("mapper/SkuAcceptanceMapper.xml"));
        return factory.getObject();
    }

    /** SqlSessionTemplate参加原Spring事务，关闭查询不释放事务持有的锁。 */
    @Bean SkuAcceptanceRepository skuAcceptanceRepository(
            @Qualifier("skuAcceptanceSqlSessionFactory") SqlSessionFactory factory) {
        return new MybatisSkuAcceptanceRepository(new SqlSessionTemplate(factory).getMapper(SkuAcceptanceMapper.class));
    }
}
