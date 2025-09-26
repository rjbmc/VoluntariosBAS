// Paquete: servlets.sevilla.bancodealimentos.es
package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import util.sevilla.bancodealimentos.es.Config;
import util.sevilla.bancodealimentos.es.DatabaseUtil;

/**
 * Servlet para que los administradores consulten las asignaciones de turnos
 * de los voluntarios para la campa�a activa.
 */
@WebServlet("/admin-asignaciones")
public class AdminAsignacionesServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;

    @Override
    public void init() throws ServletException {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new ServletException("Error: No se pudo cargar el driver de MySQL.", e);
        }
    }

    private boolean isAdmin(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session != null && session.getAttribute("isAdmin") != null && (boolean) session.getAttribute("isAdmin");
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        if (!isAdmin(request)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Acceso denegado.");
            return;
        }

        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        PrintWriter out = response.getWriter();
        StringBuilder jsonBuilder = new StringBuilder("[");

        String sql = "SELECT " +
                     "    v.Usuario, v.Nombre, v.Apellidos, " +
                     "    t1.denominacion AS TiendaTurno1, vec.Comentario1, " +
                     "    t2.denominacion AS TiendaTurno2, vec.Comentario2, " +
                     "    t3.denominacion AS TiendaTurno3, vec.Comentario3, " +
                     "    t4.denominacion AS TiendaTurno4, vec.Comentario4 " +
                     "FROM voluntarios_en_campana vec " +
                     "JOIN voluntarios v ON vec.Usuario = v.Usuario " +
                     "LEFT JOIN tiendas t1 ON vec.Turno1 = t1.codigo " +
                     "LEFT JOIN tiendas t2 ON vec.Turno2 = t2.codigo " +
                     "LEFT JOIN tiendas t3 ON vec.Turno3 = t3.codigo " +
                     "LEFT JOIN tiendas t4 ON vec.Turno4 = t4.codigo " +
                     "WHERE vec.Campana = (SELECT Campana FROM campanas WHERE estado = 'S' LIMIT 1) " +
                     "ORDER BY v.Apellidos, v.Nombre";

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            boolean first = true;
            while (rs.next()) {
                if (!first) jsonBuilder.append(",");
                jsonBuilder.append("{");
                jsonBuilder.append("\"usuario\":\"").append(escapeJson(rs.getString("Usuario"))).append("\",");
                jsonBuilder.append("\"nombre\":\"").append(escapeJson(rs.getString("Nombre"))).append("\",");
                jsonBuilder.append("\"apellidos\":\"").append(escapeJson(rs.getString("Apellidos"))).append("\",");
                jsonBuilder.append("\"tiendaTurno1\":\"").append(escapeJson(rs.getString("TiendaTurno1"))).append("\",");
                jsonBuilder.append("\"comentario1\":\"").append(escapeJson(rs.getString("Comentario1"))).append("\",");
                jsonBuilder.append("\"tiendaTurno2\":\"").append(escapeJson(rs.getString("TiendaTurno2"))).append("\",");
                jsonBuilder.append("\"comentario2\":\"").append(escapeJson(rs.getString("Comentario2"))).append("\",");
                jsonBuilder.append("\"tiendaTurno3\":\"").append(escapeJson(rs.getString("TiendaTurno3"))).append("\",");
                jsonBuilder.append("\"comentario3\":\"").append(escapeJson(rs.getString("Comentario3"))).append("\",");
                jsonBuilder.append("\"tiendaTurno4\":\"").append(escapeJson(rs.getString("TiendaTurno4"))).append("\",");
                jsonBuilder.append("\"comentario4\":\"").append(escapeJson(rs.getString("Comentario4"))).append("\"");
                jsonBuilder.append("}");
                first = false;
            }
        } catch (SQLException e) {
            e.printStackTrace();
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error al consultar las asignaciones.");
            return;
        }

        jsonBuilder.append("]");
        out.print(jsonBuilder.toString());
        out.flush();
    }
    
    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\").replace("\"", "\\\"").replace("\b", "\\b").replace("\f", "\\f")
                  .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
