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

@WebServlet("/rebuild-tiendas")
public class RebuildTiendasServlet extends HttpServlet {
    private static final long serialVersionUID = 2L; // Incrementar versión
    
    private static final Logger logger = LoggerFactory.getLogger(RebuildTiendasServlet.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        Map<String, Object> jsonResponse = new HashMap<>();
        HttpSession session = request.getSession(false);

        // --- Verificación de seguridad con nuevos roles ---
        boolean autorizado = false;
        if (session != null && session.getAttribute("usuario") != null) {
            // Intentar obtener el nuevo atributo 'rol'
            String rol = (String) session.getAttribute("rol");
            if (rol != null) {
                // Solo el Administrador ('A') puede reconstruir tiendas
                autorizado = "A".equals(rol);
            } else {
                // Fallback al antiguo isAdmin (para compatibilidad)
                Object isAdminAttr = session.getAttribute("isAdmin");
                autorizado = (isAdminAttr instanceof Boolean && (Boolean) isAdminAttr) || "S".equals(isAdminAttr);
            }
        }

        if (!autorizado) {
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
            logger.error("Error crítico durante la reconstrucción de tiendas.", e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            jsonResponse.put("success", false);
            jsonResponse.put("message", "Error interno: " + e.getMessage());
        }

        objectMapper.writeValue(response.getWriter(), jsonResponse);
    }
}