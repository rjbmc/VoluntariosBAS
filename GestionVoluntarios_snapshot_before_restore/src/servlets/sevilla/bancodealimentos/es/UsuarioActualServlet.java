package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import com.google.gson.JsonObject;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

@WebServlet("/usuario-actual")
public class UsuarioActualServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        if (session != null && session.getAttribute("usuario") != null) {
            String usuario = (String) session.getAttribute("usuario");
            boolean isAdmin = (Boolean) session.getAttribute("isAdmin");

            JsonObject jsonResponse = new JsonObject();
            jsonResponse.addProperty("usuario", usuario);
            jsonResponse.addProperty("isAdmin", isAdmin);

            response.getWriter().write(jsonResponse.toString());
        } else {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "No hay una sesiÃ³n de usuario activa.");
        }
    }
}
