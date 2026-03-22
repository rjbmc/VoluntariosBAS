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

@WebServlet("/usuario-actual")
public class UsuarioActualServlet extends HttpServlet {
    private static final long serialVersionUID = 4L;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final Logger logger = LoggerFactory.getLogger(UsuarioActualServlet.class);

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        if (session != null && session.getAttribute("usuario") != null) {
            String usuario = (String) session.getAttribute("usuario");
            
            // Obtener el rol (String) de la sesión (nuevo)
            String rol = (String) session.getAttribute("rol");
            
            // Por compatibilidad, calcular isAdmin a partir del rol o del antiguo atributo
            boolean isAdmin = false;
            if (rol != null) {
                isAdmin = "A".equals(rol);
            } else {
                // Fallback al antiguo isAdmin
                Object isAdminAttr = session.getAttribute("isAdmin");
                isAdmin = (isAdminAttr instanceof Boolean && (Boolean) isAdminAttr) || "S".equals(isAdminAttr);
                // Asignamos un rol por defecto para no dejar el campo vacío
                rol = isAdmin ? "A" : "V";
            }
            
            String nombreCompleto = (String) session.getAttribute("nombreCompleto");

            ObjectNode jsonResponse = objectMapper.createObjectNode();
            jsonResponse.put("usuario", usuario);
            jsonResponse.put("isAdmin", isAdmin);
            jsonResponse.put("rol", rol);
            jsonResponse.put("nombre", nombreCompleto);

            objectMapper.writeValue(response.getWriter(), jsonResponse);
        } else {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "No hay una sesión de usuario activa.");
        }
    }

    // El resto del código (doPost, sendJsonResponse) puede permanecer igual
    // ...
}