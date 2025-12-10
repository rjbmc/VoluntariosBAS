package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import util.sevilla.bancodealimentos.es.AsignacionRow; // Importamos el POJO desde su nuevo paquete
import util.sevilla.bancodealimentos.es.DatabaseUtil;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Servlet para que los administradores consulten las asignaciones de turnos
 * de los voluntarios para la campaña activa.
 */
@WebServlet("/admin-asignaciones")
public class AdminAsignacionesServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static final Logger logger = LogManager.getLogger(AdminAsignacionesServlet.class);
    
    // Instancia de Jackson para convertir objetos a JSON (es thread-safe y reutilizable)
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        
        // 1. Verificación de seguridad (Admin)
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("usuario") == null || !"S".equals(session.getAttribute("isAdmin"))) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        String filtroCampana = request.getParameter("campana");
        
        // Lista tipada que contendrá nuestros objetos (mucho más limpio que una lista genérica)
        List<AsignacionRow> listaAsignaciones = new ArrayList<>();

        // 2. Construcción de la consulta SQL
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT vc.Usuario, vc.Campana, vc.Turno1, vc.Turno2, vc.Turno3, vc.Turno4, ");
        sql.append("v.Nombre, v.Apellidos, v.Email, ");
        // Usamos LEFT JOIN para obtener los nombres de las tiendas, permitiendo nulos si no hay asignación
        sql.append("t1.denominacion as NombreT1, ");
        sql.append("t2.denominacion as NombreT2, ");
        sql.append("t3.denominacion as NombreT3, ");
        sql.append("t4.denominacion as NombreT4 ");
        sql.append("FROM voluntarios_en_campana vc ");
        sql.append("JOIN voluntarios v ON vc.Usuario = v.Usuario ");
        sql.append("LEFT JOIN tiendas t1 ON vc.Turno1 = t1.codigo ");
        sql.append("LEFT JOIN tiendas t2 ON vc.Turno2 = t2.codigo ");
        sql.append("LEFT JOIN tiendas t3 ON vc.Turno3 = t3.codigo ");
        sql.append("LEFT JOIN tiendas t4 ON vc.Turno4 = t4.codigo ");
        sql.append("WHERE 1=1 ");
        
        if (filtroCampana != null && !filtroCampana.isEmpty()) {
            sql.append("AND vc.Campana = ? ");
        }
        
        sql.append("ORDER BY v.Apellidos, v.Nombre");

        // 3. Ejecución y Mapeo de Datos
        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            
            if (filtroCampana != null && !filtroCampana.isEmpty()) {
                stmt.setString(1, filtroCampana);
            }

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    // Creamos una instancia del POJO por cada fila
                    AsignacionRow row = new AsignacionRow();
                    
                    // Rellenamos los datos básicos
                    row.setUsuario(rs.getString("Usuario"));
                    row.setNombreCompleto(rs.getString("Nombre") + " " + rs.getString("Apellidos"));
                    row.setEmail(rs.getString("Email"));
                    row.setCampana(rs.getString("Campana"));
                    
                    // Rellenamos datos de turnos (Jackson manejará los nulos correctamente en el JSON)
                    row.setTurno1(rs.getInt("Turno1"));
                    row.setNombreTienda1(rs.getString("NombreT1"));
                    
                    row.setTurno2(rs.getInt("Turno2"));
                    row.setNombreTienda2(rs.getString("NombreT2"));
                    
                    row.setTurno3(rs.getInt("Turno3"));
                    row.setNombreTienda3(rs.getString("NombreT3"));
                    
                    row.setTurno4(rs.getInt("Turno4"));
                    row.setNombreTienda4(rs.getString("NombreT4"));

                    // Añadimos a la lista
                    listaAsignaciones.add(row);
                }
            }
        } catch (SQLException e) {
            logger.error("Error al obtener asignaciones", e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return;
        }

        // 4. Devolución de Resultados
        mapper.writeValue(response.getOutputStream(), listaAsignaciones);
    }
}