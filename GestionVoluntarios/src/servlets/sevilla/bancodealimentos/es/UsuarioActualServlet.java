package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

@WebServlet("/usuario-actual")
public class UsuarioActualServlet extends HttpServlet {
    private static final long serialVersionUID = 3L; // Versión actualizada
    private final ObjectMapper objectMapper = new ObjectMapper();

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
}
