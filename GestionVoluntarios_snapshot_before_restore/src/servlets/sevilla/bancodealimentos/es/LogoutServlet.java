// Paquete: servlets.sevilla.bancodealimentos.es
package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import util.sevilla.bancodealimentos.es.LogUtil;

/**
 * Servlet para gestionar el cierre de sesiÃ³n de los usuarios.
 */
@WebServlet("/logout")
public class LogoutServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        
        HttpSession session = request.getSession(false);
        
        if (session != null) {
            String usuario = (String) session.getAttribute("usuario");
            
            if (usuario != null) {
                // --- CAMBIO: Se elimina el Ãºltimo ParÃ¡metro de la llamada al log ---
                LogUtil.logOperation("LOGOUT", usuario, "Cierre de sesiÃ³n exitoso.");
            }
            
            session.invalidate();
        }
        
        response.setStatus(HttpServletResponse.SC_OK);
        response.getWriter().print("{\"success\": true, \"message\": \"sesiÃ³n cerrada correctamente.\"}");
    }
}

