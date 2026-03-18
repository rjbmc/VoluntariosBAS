package util.sevilla.bancodealimentos.es;

import java.sql.Connection;
import java.sql.SQLException;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DatabaseUtil {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseUtil.class);
    private static volatile DataSource dataSource;

    public static Connection getConnection() throws SQLException {
        DataSource ds = getDataSource();
        if (ds == null) {
            throw new SQLException("DataSource no inicializado. Verifique el recurso JNDI 'jdbc/VoluntariosDB'.");
        }
        return ds.getConnection();
    }

    private static DataSource getDataSource() {
        if (dataSource == null) {
            synchronized (DatabaseUtil.class) {
                if (dataSource == null) {
                    try {
                        Context initCtx = new InitialContext();
                        Context envCtx = (Context) initCtx.lookup("java:comp/env");
                        dataSource = (DataSource) envCtx.lookup("jdbc/VoluntariosDB");
                        logger.info("DataSource JNDI 'jdbc/VoluntariosDB' inicializado correctamente.");
                    } catch (NamingException e) {
                        logger.error("Error al obtener DataSource JNDI: " + e.getMessage(), e);
                        // dataSource remains null, will be retried on next call
                    }
                }
            }
        }
        return dataSource;
    }
}