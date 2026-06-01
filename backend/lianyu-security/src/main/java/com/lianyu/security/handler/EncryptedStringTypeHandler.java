package com.lianyu.security.handler;

import com.lianyu.common.util.SpringContextHolder;
import com.lianyu.security.util.JasyptUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.*;

@Slf4j
public class EncryptedStringTypeHandler extends BaseTypeHandler<String> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType) throws SQLException {
        ps.setString(i, getJasyptUtil().encrypt(parameter));
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        String encrypted = rs.getString(columnName);
        return encrypted != null ? getJasyptUtil().decrypt(encrypted) : null;
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        String encrypted = rs.getString(columnIndex);
        return encrypted != null ? getJasyptUtil().decrypt(encrypted) : null;
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        String encrypted = cs.getString(columnIndex);
        return encrypted != null ? getJasyptUtil().decrypt(encrypted) : null;
    }

    private JasyptUtil getJasyptUtil() {
        return SpringContextHolder.getBean(JasyptUtil.class);
    }
}
