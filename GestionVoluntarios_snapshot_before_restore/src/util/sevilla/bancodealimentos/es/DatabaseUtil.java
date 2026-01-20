package util.sevilla.bancodealimentos.es;

import java.sql.Connection;
import java.sql.SQLException;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;

public class DatabaseUtil {
    private static DataSource dataSource;

    static {
        try {
            // Busca la conexiÃ³n configurada en Tomcat por su nombre JNDI
            Context initContext = new InitialContext();
            Context envContext = (Context) initContext.lookup("java:/comp/env");
            dataSource = (DataSource) envContext.lookup("jdbc/VoluntariosDB");
        } catch (NamingException e) {
            System.err.println("ERROR CRÃTICO: No se pudo encontrar el Data Source JNDI 'jdbc/VoluntariosDB'.");
            e.printStackTrace();
        }
    }
    public static Connection getConnection() throws SQLException {
        if (dataSource == null) {
            throw new SQLException("El Data Source no estÃ¡ inicializado. Revisa la configuraciÃ³n del servidor.");
        }
        return dataSource.getConnection();
    }
}
