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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Servlet que devuelve los turnos ya asignados de un voluntario.
 * Si es llamado por un admin con un par�metro 'usuario', devuelve los datos de ese usuario.
 * Si no, devuelve los datos del usuario en sesi�n.
 */
@WebServlet("/mis-turnos")
public class MisTurnosServlet extends HttpServlet {
    private static final long serialVersionUID = 2L; // Versi�n actualizada
    private static final Logger logger = LogManager.getLogger(MisTurnosServlet.class);

    private boolean isAdmin(HttpSession session) {
        if (session == null) return false;
        Object isAdminAttr = session.getAttribute("isAdmin");
        return isAdminAttr != null && (boolean) isAdminAttr;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        PrintWriter out = response.getWriter();

        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("usuario") == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "No hay una sesi�n de usuario activa.");
            return;
        }

        String usuarioAConsultar = request.getParameter("usuario");
        String usuarioEnSesion = (String) session.getAttribute("usuario");
        String usuarioFinal;

        if (isAdmin(session) && usuarioAConsultar != null && !usuarioAConsultar.trim().isEmpty()) {
            // Un administrador est� pidiendo los datos de un voluntario espec�fico.
            usuarioFinal = usuarioAConsultar;
        } else {
            // Un voluntario normal pide sus propios datos.
            usuarioFinal = usuarioEnSesion;
        }

        String campanaId = request.getParameter("campana");
        if (campanaId == null || campanaId.trim().isEmpty()) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "El par�metro 'campana' es requerido.");
            return;
        }

        String sql = "SELECT Turno1, Comentario1, Turno2, Comentario2, Turno3, Comentario3, Turno4, Comentario4 FROM voluntarios_en_campana WHERE Usuario = ? AND Campana = ?";

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, usuarioFinal);
            stmt.setString(2, campanaId);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    StringBuilder jsonBuilder = new StringBuilder("{");
                    jsonBuilder.append("\"Turno1\":").append(rs.getInt("Turno1")).append(",");
                    jsonBuilder.append("\"Comentario1\":\"").append(escapeJson(rs.getString("Comentario1"))).append("\",");
                    jsonBuilder.append("\"Turno2\":").append(rs.getInt("Turno2")).append(",");
                    jsonBuilder.append("\"Comentario2\":\"").append(escapeJson(rs.getString("Comentario2"))).append("\",");
                    jsonBuilder.append("\"Turno3\":").append(rs.getInt("Turno3")).append(",");
                    jsonBuilder.append("\"Comentario3\":\"").append(escapeJson(rs.getString("Comentario3"))).append("\",");
                    jsonBuilder.append("\"Turno4\":").append(rs.getInt("Turno4")).append(",");
                    jsonBuilder.append("\"Comentario4\":\"").append(escapeJson(rs.getString("Comentario4"))).append("\"");
                    jsonBuilder.append("}");
                    out.print(jsonBuilder.toString());
                } else {
                    response.setStatus(HttpServletResponse.SC_OK);
                    out.print("{}"); // Devolver un objeto vac�o si no hay turnos
                }
            }
        } catch (SQLException e) {
            logger.error("Error al consultar los turnos para usuario=" + usuarioFinal + " campana=" + campanaId, e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error al consultar la base de datos.");
        }
        
        out.flush();
    }

    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\").replace("\"", "\\\"").replace("\b", "\\b").replace("\f", "\\f")
                  .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}