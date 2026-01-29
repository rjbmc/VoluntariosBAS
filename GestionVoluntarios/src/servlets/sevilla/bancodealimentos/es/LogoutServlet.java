package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
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

@WebServlet("/logout")
public class LogoutServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static final Logger logger = LoggerFactory.getLogger(LogoutServlet.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        HttpSession session = request.getSession(false);
        String usuario = "Anónimo";

        if (session != null && session.getAttribute("usuario") != null) {
            usuario = (String) session.getAttribute("usuario");
        }

        String context = "Usuario: " + usuario;

        try (Connection conn = DatabaseUtil.getConnection()) {
            LogUtil.logOperation(conn, "LOGOUT", usuario, "Cierre de sesión.");
        } catch (Exception e) {
            LogUtil.logException(logger, e, "Error registrando el logout en la BD", context);
        }

        if (session != null) {
            session.invalidate();
        }
        
        sendJsonResponse(response, HttpServletResponse.SC_OK, true, "Sesión cerrada correctamente.");
    }
    
    private void sendJsonResponse(HttpServletResponse response, int status, boolean success, String message) throws IOException {
        if (!response.isCommitted()) {
            response.setStatus(status);
            Map<String, Object> jsonResponse = new HashMap<>();
            jsonResponse.put("success", success);
            jsonResponse.put("message", message);
            objectMapper.writeValue(response.getWriter(), jsonResponse);
        }
    }
}
