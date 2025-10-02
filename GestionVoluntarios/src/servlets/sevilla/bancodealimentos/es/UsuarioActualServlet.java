package servlets.sevilla.bancodealimentos.es;


import java.io.IOException;
import java.io.PrintWriter;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * Servlet que devuelve el nombre del usuario actualmente autenticado
 * a partir de la sesi�n HTTP.
 */
@WebServlet("/usuario-actual")
public class UsuarioActualServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
    	// Obtenemos la sesión actual. El 'false' indica que no se debe crear una nueva si no existe.
    	HttpSession session = request.getSession(false);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        PrintWriter out = response.getWriter();

        // Obtenemos la sesi�n actual. El 'false' indica que no se debe crear una nueva si no existe.

        if (session != null && session.getAttribute("usuario") != null) {
            // Si hay una sesi�n y contiene el atributo 'usuario', lo devolvemos.
            String usuario = (String) session.getAttribute("usuario");
            
            // Construimos una respuesta JSON simple: {"usuario": "nombredeusuario"}
            out.print("{\"usuario\": \"" + usuario + "\"}");
            out.flush();
        } else {
            // Si no hay sesi�n o no hay usuario en la sesi�n, significa que no est� autenticado.
            // Enviamos un error 401 Unauthorized.
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "No hay una sesi�n de usuario activa.");
        }
    }    

}
