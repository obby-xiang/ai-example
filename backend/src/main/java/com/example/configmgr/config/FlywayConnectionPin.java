package com.example.configmgr.config;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Flyway 迁移期“钉住”一个物理连接：<b>PostgreSQL</b> 的锁与 {@code migrate()} 必须同连接
 * （会话级 advisory lock 绑定连接）。H2 没有可跨提交保持的会话级锁，H2 路径不使用本 pin。
 *
 * <p>Flyway 只接受 {@link DataSource}（11.x 已移除 Connection 入口），所以用一个只在本线程内
 * 转发到 pinned Connection 的 DataSource 包装；{@link #pin(Connection)} 返回的句柄关闭后恢复原行为。
 */
final class FlywayConnectionPin {

    private final ThreadLocal<Connection> pinned = new ThreadLocal<>();

    AutoCloseable pin(Connection connection) {
        Objects.requireNonNull(connection, "connection");
        Connection previous = this.pinned.get();
        this.pinned.set(connection);
        return () -> {
            if (previous == null) {
                this.pinned.remove();
            }
            else {
                this.pinned.set(previous);
            }
        };
    }

    Connection current() {
        return this.pinned.get();
    }

    /**
     * 供 Flyway 使用的 DataSource：本线程有 pin 时返回同一物理连接的非关闭代理，
     * 否则回落到真实 DataSource（启动期之外的正常借用不受影响）。
     */
    static final class PinnedDataSource implements DataSource {

        private final DataSource delegate;

        private final FlywayConnectionPin pin;

        PinnedDataSource(DataSource delegate, FlywayConnectionPin pin) {
            this.delegate = delegate;
            this.pin = pin;
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection pinned = this.pin.current();
            return pinned == null ? this.delegate.getConnection() : NonClosingConnection.wrap(pinned);
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            Connection pinned = this.pin.current();
            return pinned == null ? this.delegate.getConnection(username, password)
                    : NonClosingConnection.wrap(pinned);
        }

        @Override
        public java.io.PrintWriter getLogWriter() throws SQLException {
            return this.delegate.getLogWriter();
        }

        @Override
        public void setLogWriter(java.io.PrintWriter out) throws SQLException {
            this.delegate.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            this.delegate.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return this.delegate.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return this.delegate.getParentLogger();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return this.delegate.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return this.delegate.isWrapperFor(iface);
        }
    }

    /** 不真正关闭底层连接的 Connection 代理（Flyway 自己会关它借到的连接）。 */
    interface NonClosingConnection {

        static Connection wrap(Connection target) {
            return (Connection) java.lang.reflect.Proxy.newProxyInstance(
                    NonClosingConnection.class.getClassLoader(), new Class<?>[] { Connection.class },
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if ("close".equals(name)) {
                            return null;
                        }
                        if ("isClosed".equals(name)) {
                            return false;
                        }
                        if ("unwrap".equals(name) && args != null && args.length == 1
                                && ((Class<?>) args[0]).isInstance(target)) {
                            return target;
                        }
                        try {
                            return method.invoke(target, args);
                        }
                        catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }
    }
}
