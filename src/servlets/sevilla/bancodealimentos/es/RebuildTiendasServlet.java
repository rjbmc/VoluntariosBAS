package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
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
import util.sevilla.bancodealimentos.es.TiendasUtil;

/**
 * Servlet encargado de reconstruir la tabla de tiendas a partir de una fuente externa
 * o fichero maestro. Esta es una operación administrativa crítica.
 */
@WebServlet("/rebuild-tiendas")
public class RebuildTiendasServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    
    // 1. Logger SLF4J
    private static final Logger logger = LoggerFactory.getLogger(RebuildTiendasServlet.class);
    
    // 2. Jackson ObjectMapper (Reutilizable y Thread-safe)
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        Map<String, Object> jsonResponse = new HashMap<>();
        HttpSession session = request.getSession(false);

        // 3. Verificación de Seguridad Estandarizada
        // Usamos la misma lógica que en AdminTiendasServlet para evitar ClassCastException
        // si el atributo isAdmin se guarda como String "S" en lugar de Boolean true.
        boolean isAdmin = session != null && 
                          session.getAttribute("usuario") != null && 
                          "S".equals(session.getAttribute("isAdmin"));

        if (!isAdmin) {
            String ip = request.getRemoteAddr();
            logger.warn("Acceso denegado a RebuildTiendas. Usuario: {}, IP: {}", 
                        (session != null ? session.getAttribute("usuario") : "Anónimo"), ip);
            
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            jsonResponse.put("success", false);
            jsonResponse.put("message", "Acceso denegado. Solo los administradores pueden realizar esta acción.");
            objectMapper.writeValue(response.getWriter(), jsonResponse);
            return;
        }

        String adminUser = (String) session.getAttribute("usuario");
        logger.info("El administrador {} ha iniciado la reconstrucción de tiendas.", adminUser);

        try {
            // Llamada a la utilidad que realiza la lógica pesada
            boolean success = TiendasUtil.rebuildTiendas();

            if (success) {
                logger.info("Reconstrucción de tiendas completada con éxito por {}", adminUser);
                jsonResponse.put("success", true);
                jsonResponse.put("message", "Las tiendas se han reconstruido correctamente.");
            } else {
                logger.warn("La reconstrucción de tiendas finalizó pero devolvió 'false'.");
                jsonResponse.put("success", false);
                jsonResponse.put("message", "Hubo un problema al reconstruir las tiendas. Revise el log del servidor.");
            }
        } catch (Exception e) {
            // 4. Manejo de errores con traza completa en el log
            logger.error("Error crítico durante la reconstrucción de tiendas.", e);
            
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            jsonResponse.put("success", false);
            jsonResponse.put("message", "Error interno: " + e.getMessage());
        }

        // Escritura final con Jackson
        objectMapper.writeValue(response.getWriter(), jsonResponse);
    }
}