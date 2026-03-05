package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import util.sevilla.bancodealimentos.es.DatabaseUtil;
import util.sevilla.bancodealimentos.es.LogUtil;
import util.sevilla.bancodealimentos.es.PasswordUtils;

@WebServlet("/usuario-actual")
public class UsuarioActualServlet extends HttpServlet {
    private static final long serialVersionUID = 3L; // Versión actualizada
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final Logger logger = LoggerFactory.getLogger(LoginServlet.class);
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        if (session != null && session.getAttribute("usuario") != null) {
            String usuario = (String) session.getAttribute("usuario");
            boolean isAdmin = (Boolean) session.getAttribute("isAdmin");
            String nombreCompleto = (String) session.getAttribute("nombreCompleto");

            ObjectNode jsonResponse = objectMapper.createObjectNode();
            jsonResponse.put("usuario", usuario);
            jsonResponse.put("isAdmin", isAdmin);
            jsonResponse.put("nombre", nombreCompleto); // Añadimos el nombre completo

            objectMapper.writeValue(response.getWriter(), jsonResponse);
        } else {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "No hay una sesión de usuario activa.");
        }
    }
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        String usuario = request.getParameter("usuario") != null ? request.getParameter("usuario").trim() : "";
        String clave = request.getParameter("clave") != null ? request.getParameter("clave") : "";
        String remoteAddr = request.getRemoteAddr();
        String context = String.format("Usuario: %s, IP: %s", usuario, remoteAddr);
        
        ObjectNode jsonResponse = objectMapper.createObjectNode();
        Connection conn = null;
        String isAdmin;
        String nombreCompleto;
        try {
            conn = DatabaseUtil.getConnection();
            String sql = "SELECT Nombre, Apellidos, Email, Clave, administrador, verificado, fecha_baja FROM voluntarios WHERE Usuario = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, usuario);
                try (ResultSet rs = ps.executeQuery()) {
                	 if (rs.next()) {
                         isAdmin = rs.getString("administrador");
                         nombreCompleto = (rs.getString("Nombre") + " " + rs.getString("Apellidos")).trim();
                         jsonResponse.put("usuario", usuario);
                         jsonResponse.put("isAdmin", isAdmin);
                         jsonResponse.put("nombre", nombreCompleto); // Añadimos el nombre completo
                         objectMapper.writeValue(response.getWriter(), jsonResponse);
                	 }
                }
            }
        }
        catch (Exception e) {
        	LogUtil.logException(logger, e, "Error inesperado buscando usuario", context);
        }
    }
	private void sendJsonResponse(HttpServletResponse response, int status, boolean success, String message) throws IOException {
	       sendJsonResponse(response, status, success, message, null);
	}
	    
    private void sendJsonResponse(HttpServletResponse response, int status, boolean success, String message, Map<String, Object> data) throws IOException {
        if (!response.isCommitted()) {
            response.setStatus(status);
            Map<String, Object> jsonResponse = new HashMap<>();
            jsonResponse.put("success", success);
            jsonResponse.put("message", message);
            if (data != null) {
                jsonResponse.putAll(data);
            }
            objectMapper.writeValue(response.getWriter(), jsonResponse);
        }
            	    }
}
