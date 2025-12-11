package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import util.sevilla.bancodealimentos.es.DatabaseUtil;

@WebServlet("/mis-turnos")
public class MisTurnosServlet extends HttpServlet {
    private static final long serialVersionUID = 3L; // Versión actualizada
    private final ObjectMapper objectMapper = new ObjectMapper();

    private boolean isAdmin(HttpSession session) {
        if (session == null) return false;
        Object isAdminAttr = session.getAttribute("isAdmin");
        return isAdminAttr != null && (boolean) isAdminAttr;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("usuario") == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "No hay una sesión de usuario activa.");
            return;
        }

        String usuarioAConsultar = request.getParameter("usuario");
        String usuarioEnSesion = (String) session.getAttribute("usuario");
        String usuarioFinal;

        if (isAdmin(session) && usuarioAConsultar != null && !usuarioAConsultar.trim().isEmpty()) {
            usuarioFinal = usuarioAConsultar;
        } else {
            usuarioFinal = usuarioEnSesion;
        }

        String campanaId = request.getParameter("campana");
        if (campanaId == null || campanaId.trim().isEmpty()) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "El parámetro 'campana' es requerido.");
            return;
        }

        String sql = "SELECT Turno1, Comentario1, Turno2, Comentario2, Turno3, Comentario3, Turno4, Comentario4 FROM voluntarios_en_campana WHERE Usuario = ? AND Campana = ?";

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, usuarioFinal);
            stmt.setString(2, campanaId);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    ObjectNode turnosJson = objectMapper.createObjectNode();
                    turnosJson.put("Turno1", rs.getInt("Turno1"));
                    turnosJson.put("Comentario1", rs.getString("Comentario1") != null ? rs.getString("Comentario1") : "");
                    turnosJson.put("Turno2", rs.getInt("Turno2"));
                    turnosJson.put("Comentario2", rs.getString("Comentario2") != null ? rs.getString("Comentario2") : "");
                    turnosJson.put("Turno3", rs.getInt("Turno3"));
                    turnosJson.put("Comentario3", rs.getString("Comentario3") != null ? rs.getString("Comentario3") : "");
                    turnosJson.put("Turno4", rs.getInt("Turno4"));
                    turnosJson.put("Comentario4", rs.getString("Comentario4") != null ? rs.getString("Comentario4") : "");
                    
                    objectMapper.writeValue(response.getWriter(), turnosJson);
                } else {
                    response.setStatus(HttpServletResponse.SC_OK);
                    objectMapper.writeValue(response.getWriter(), objectMapper.createObjectNode()); // Devolver objeto vacío
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error al consultar la base de datos.");
        }
    }
}
