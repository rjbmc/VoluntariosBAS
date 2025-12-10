package util.sevilla.bancodealimentos.es;

import java.sql.Connection;
import java.sql.SQLException;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class DatabaseUtil {
    private static final Logger logger = LogManager.getLogger(DatabaseUtil.class);
    private static DataSource dataSource;

    static {
        try {
            // Busca la conexión configurada en Tomcat por su nombre JNDI
            Context initContext = new InitialContext();
            Context envContext = (Context) initContext.lookup("java:/comp/env");
            dataSource = (DataSource) envContext.lookup("jdbc/VoluntariosDB");
        } catch (NamingException e) {
            logger.error("ERROR CRÍTICO: No se pudo encontrar el Data Source JNDI 'jdbc/VoluntariosDB'.", e);
        }
    }
    public static Connection getConnection() throws SQLException {
        if (dataSource == null) {
            throw new SQLException("El Data Source no está inicializado. Revisa la configuración del servidor.");
        }
        return dataSource.getConnection();
    }
}