package util.sevilla.bancodealimentos.es;

import java.sql.Connection;
import java.sql.SQLException;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Clase de utilidad para obtener conexiones a la base de datos vía JNDI.
 * Centraliza la gestión del DataSource configurado en el servidor (Tomcat).
 */
public class DatabaseUtil {
    
    // 1. Logger SLF4J
    private static final Logger logger = LoggerFactory.getLogger(DatabaseUtil.class);
    
    private static DataSource dataSource;

    static {
        try {
            // Busca la conexión configurada en Tomcat por su nombre JNDI
            Context initContext = new InitialContext();
            Context envContext = (Context) initContext.lookup("java:/comp/env");
            dataSource = (DataSource) envContext.lookup("jdbc/VoluntariosDB");
            
            // Confirmación de éxito en el log (nivel INFO)
            logger.info("DataSource JNDI 'jdbc/VoluntariosDB' inicializado y localizado correctamente.");
            
        } catch (NamingException e) {
            // Registro de error crítico (nivel ERROR) con traza completa
            logger.error("ERROR CRÍTICO: No se pudo encontrar el Data Source JNDI 'jdbc/VoluntariosDB'. Verifique el fichero context.xml.", e);
            // Nota: Aunque falle aquí, la excepción no se puede propagar fuera del bloque estático,
            // pero dataSource quedará null y fallará en getConnection().
        }
    }

    public static Connection getConnection() throws SQLException {
        if (dataSource == null) {
            logger.error("Intento de obtener conexión a base de datos fallido: DataSource es nulo.");
            throw new SQLException("El Data Source no está inicializado. Revisa la configuración del servidor y los logs de arranque.");
        }
        return dataSource.getConnection();
    }
}