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
 * Servlet que devuelve una lista de puntos de recogida (tiendas)
 * que tienen huecos disponibles para un turno espec�fico, incluyendo su prioridad.
 */
@WebServlet("/puntos-disponibles")
public class PuntosDisponiblesServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;

    private boolean isAdmin(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session != null && session.getAttribute("isAdmin") != null && (boolean) session.getAttribute("isAdmin");
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        
        String turnoParam = request.getParameter("turno");
        if (turnoParam == null || !turnoParam.matches("[1-4]")) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Par�metro 'turno' (1-4) es requerido.");
            return;
        }
        
        String campanaId = request.getParameter("campana");
         if (campanaId == null || campanaId.trim().isEmpty()) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Par�metro 'campana' es requerido.");
            return;
        }

        String huecosTurnoCol = "HuecosTurno" + turnoParam;
        String turnoCol = "Turno" + turnoParam;

        PrintWriter out = response.getWriter();
        StringBuilder jsonBuilder = new StringBuilder("[");

        // --- CAMBIO: La consulta ahora tambi�n selecciona la columna 'prioridad' ---
        String sql = "SELECT t.codigo AS id, t.denominacion AS nombre, t.Direccion AS direccion, t.Lat AS lat, t.Lon AS lon, t.prioridad " +
                     "FROM tiendas t " +
                     "LEFT JOIN ( " +
                     "    SELECT " + turnoCol + " as tienda_id, COUNT(*) as ocupados " +
                     "    FROM voluntarios_en_campana " +
                     "    WHERE Campana = ? AND " + turnoCol + " > 0 " +
                     "    GROUP BY " + turnoCol +
                     ") v ON t.codigo = v.tienda_id " +
                     "WHERE t." + huecosTurnoCol + " > IFNULL(v.ocupados, 0) AND t.disponible = 'S'";

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, campanaId);

            try (ResultSet rs = stmt.executeQuery()) {
                boolean first = true;
                while (rs.next()) {
                    if (!first) jsonBuilder.append(",");
                    jsonBuilder.append("{");
                    jsonBuilder.append("\"id\":").append(rs.getInt("id")).append(",");
                    jsonBuilder.append("\"nombre\":\"").append(escapeJson(rs.getString("nombre"))).append("\",");
                    jsonBuilder.append("\"direccion\":\"").append(escapeJson(rs.getString("direccion"))).append("\",");
                    jsonBuilder.append("\"lat\":").append(rs.getBigDecimal("lat")).append(",");
                    jsonBuilder.append("\"lon\":").append(rs.getBigDecimal("lon")).append(",");
                    // --- CAMBIO: Se a�ade la prioridad al JSON de respuesta ---
                    jsonBuilder.append("\"prioridad\":").append(rs.getInt("prioridad"));
                    jsonBuilder.append("}");
                    first = false;
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error al consultar la base de datos.");
            return;
        }

        jsonBuilder.append("]");
        out.print(jsonBuilder.toString());
        out.flush();
    }

    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\").replace("\"", "\\\"").replace("\b", "\\b").replace("\f", "\\f")
                  .replace("\n", "\\n").replace("\r", "\\r").replace("\t", " ");
    }
}
