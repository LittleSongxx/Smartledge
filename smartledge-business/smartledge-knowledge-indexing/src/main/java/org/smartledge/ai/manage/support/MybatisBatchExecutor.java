package org.smartledge.ai.manage.support;

import com.baomidou.mybatisplus.core.enums.SqlMethod;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import com.baomidou.mybatisplus.extension.toolkit.SqlHelper;
import org.apache.ibatis.logging.LogFactory;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Small wrapper around MyBatis-Plus batch execution for hot document build paths.
 */
public final class MybatisBatchExecutor {

    private static final int DEFAULT_BATCH_SIZE = 200;

    private MybatisBatchExecutor() {
    }

    public static <T> void insertBatch(Class<T> entityClass, Collection<T> records) {
        if (entityClass == null || records == null || records.isEmpty()) {
            return;
        }
        String sqlStatement = sqlStatement(entityClass, SqlMethod.INSERT_ONE);
        SqlHelper.executeBatch(entityClass, LogFactory.getLog(entityClass), records, DEFAULT_BATCH_SIZE,
            (sqlSession, record) -> sqlSession.insert(sqlStatement, record));
    }

    public static <T> void updateBatchById(Class<T> entityClass, Collection<T> records) {
        if (entityClass == null || records == null || records.isEmpty()) {
            return;
        }
        String sqlStatement = sqlStatement(entityClass, SqlMethod.UPDATE_BY_ID);
        SqlHelper.executeBatch(entityClass, LogFactory.getLog(entityClass), records, DEFAULT_BATCH_SIZE,
            (sqlSession, record) -> sqlSession.update(sqlStatement, entityParam(record)));
    }

    private static String sqlStatement(Class<?> entityClass, SqlMethod sqlMethod) {
        TableInfo tableInfo = SqlHelper.table(entityClass);
        return tableInfo.getSqlStatement(sqlMethod.getMethod());
    }

    private static <T> Map<String, Object> entityParam(T record) {
        Map<String, Object> param = new HashMap<>(1);
        param.put(Constants.ENTITY, record);
        return param;
    }
}
