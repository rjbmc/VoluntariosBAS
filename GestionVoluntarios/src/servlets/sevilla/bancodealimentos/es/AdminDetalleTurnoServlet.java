package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import util.sevilla.bancodealimentos.es.DatabaseUtil;
import util.sevilla.bancodealimentos.es.LogUtil;

@WebServlet("/admin-detalle-turno")
public class AdminDetalleTurnoServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static final Logger logger = LoggerFactory.getLogger(AdminDetalleTurnoServlet.class);
    private final ObjectMapper mapper = new ObjectMapper();

    // DTO para la respuesta
    public static class VoluntarioTurnoDTO {
        public String nombre;
        public String email;
        public String telefono;
        public int acompanantes;
    }

    // --- Métodos de Sesión y Permisos ---
    private String getUsuario(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return (session != null && session.getAttribute("usuario") != null) ? (String) session.getAttribute("usuario") : "Anónimo";
    }

    private boolean isAdmin(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("usuario") == null) return false;
        Object isAdminAttr = session.getAttribute("isAdmin");
        return "S".equals(isAdminAttr) || (isAdminAttr instanceof Boolean && (Boolean) isAdminAttr);
    }

    // --- Método Principal (doGet) ---
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        String adminUser = getUsuario(request);

        if (!isAdmin(request)) {
            LogUtil.logException(logger, new SecurityException("Acceso denegado"), "Intento de acceso a /admin-detalle-turno", adminUser);
            sendJsonResponse(response, HttpServletResponse.SC_FORBIDDEN, false, "Acceso denegado.");
            return;
        }

        String campanaId = request.getParameter("campana");
        String tiendaIdStr = request.getParameter("tienda");
        String turnoNum = request.getParameter("turno");
        String context = String.format("Admin: %s, Campaña: %s, Tienda: %s, Turno: %s", adminUser, campanaId, tiendaIdStr, turnoNum);

        if (campanaId == null || tiendaIdStr == null || turnoNum == null || !turnoNum.matches("[1-4]")) {
            sendJsonResponse(response, HttpServletResponse.SC_BAD_REQUEST, false, "Parámetros 'campana', 'tienda' y 'turno' (1-4) son requeridos.");
            return;
        }

        List<VoluntarioTurnoDTO> listaVoluntarios = new ArrayList<>();
        String turnoCol = "Turno" + turnoNum;
        String comentarioCol = "Comentario" + turnoNum;
        String sql = "SELECT concat(v.Nombre, ' ', v.Apellidos, ' (', v.Usuario, ')') as Voluntario, v.Email, v.telefono, vec." + comentarioCol + " AS Comentario " +
                     "FROM voluntarios_en_campana vec JOIN voluntarios v ON vec.Usuario = v.Usuario " +
                     "WHERE vec.Campana = ? AND vec." + turnoCol + " = ?";

        Connection conn = null;
        try {
            conn = DatabaseUtil.getConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, campanaId);
                stmt.setInt(2, Integer.parseInt(tiendaIdStr));
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        VoluntarioTurnoDTO dto = new VoluntarioTurnoDTO();
                        dto.nombre = rs.getString("Voluntario");
                        dto.email = rs.getString("Email");
                        dto.telefono = rs.getString("telefono");
                        dto.acompanantes = parseAcompanantes(rs.getString("Comentario"));
                        listaVoluntarios.add(dto);
                    }
                }
            }
            mapper.writeValue(response.getWriter(), listaVoluntarios);
        } catch (NumberFormatException e) {
            LogUtil.logException(logger, e, "Parámetro de tienda inválido en /admin-detalle-turno", context);
            sendJsonResponse(response, HttpServletResponse.SC_BAD_REQUEST, false, "El ID de tienda debe ser un número.");
        } catch (SQLException e) {
            LogUtil.logException(logger, e, "Error de BD al consultar detalle de turno", context);
            sendJsonResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, false, "Error de base de datos. El problema ha sido registrado.");
        } catch (Exception e) {
            LogUtil.logException(logger, e, "Error inesperado en /admin-detalle-turno", context);
            sendJsonResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, false, "Error inesperado. El problema ha sido registrado.");
        } finally {
            if (conn != null) try { conn.close(); } catch (SQLException e) { LogUtil.logException(logger, e, "Error cerrando conexión en doGet(DetalleTurno)", context); }
        }
    }

    // --- Métodos de Utilidad ---
    private int parseAcompanantes(String comentario) {
        if (comentario != null && comentario.startsWith("Voluntarios: ")) {
            try {
                String numStr = comentario.substring("Voluntarios: ".length()).split("\\.")[0].trim();
                return Integer.parseInt(numStr);
            } catch (Exception e) {
                // No es necesario loguear un error, puede que no haya acompañantes
            }
        }
        return 0;
    }

    private void sendJsonResponse(HttpServletResponse response, int status, boolean success, String message) throws IOException {
        if (!response.isCommitted()) {
            response.setStatus(status);
            Map<String, Object> res = new HashMap<>();
            res.put("success", success);
            res.put("message", message);
            mapper.writeValue(response.getWriter(), res);
        }
    }
}