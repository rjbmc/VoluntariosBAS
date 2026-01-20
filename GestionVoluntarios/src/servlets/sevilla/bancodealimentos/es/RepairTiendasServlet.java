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
import util.sevilla.bancodealimentos.es.RepairTiendasData;

/**
 * Servlet para activar manualmente el proceso de reparación de SqlRowUUIDs en SharePoint.
 * Accesible solo para administradores.
 */
@WebServlet("/admin/repair-uuids")
public class RepairTiendasServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static final Logger logger = LoggerFactory.getLogger(RepairTiendasServlet.class);
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Verifica de forma robusta si el usuario en sesión tiene permisos de administrador.
     */
    private boolean isAdmin(HttpSession session) {
        if (session == null || session.getAttribute("usuario") == null) return false;
        
        Object isAdminAttr = session.getAttribute("isAdmin");
        if (isAdminAttr instanceof Boolean) {
            return (Boolean) isAdminAttr;
        } else if (isAdminAttr instanceof String) {
            return "S".equals(isAdminAttr);
        }
        return false;
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        
        Map<String, Object> jsonResponse = new HashMap<>();
        HttpSession session = request.getSession(false);

        // 1. Control de seguridad
        if (!isAdmin(session)) {
            logger.warn("Intento de ejecución de reparación no autorizado desde IP: {}", request.getRemoteAddr());
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            jsonResponse.put("success", false);
            jsonResponse.put("message", "Acceso denegado. Se requieren permisos de administrador.");
            mapper.writeValue(response.getWriter(), jsonResponse);
            return;
        }

        String adminUser = (String) session.getAttribute("usuario");
        logger.info("El administrador '{}' ha iniciado manualmente la reparación de UUIDs.", adminUser);

        try {
            // 2. Ejecución del proceso de reparación definido en la utilidad
            // Nota: Este proceso es sincrónico y puede tardar varios segundos si hay muchas tiendas.
            RepairTiendasData.repairSharePointUUIDs();

            jsonResponse.put("success", true);
            jsonResponse.put("message", "Proceso de reparación finalizado. Revisa los logs del servidor para ver el detalle de los registros actualizados.");
            
        } catch (Exception e) {
            logger.error("Error al ejecutar el proceso de reparación solicitado por {}", adminUser, e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            jsonResponse.put("success", false);
            jsonResponse.put("message", "Error crítico durante la reparación: " + e.getMessage());
        }

        mapper.writeValue(response.getWriter(), jsonResponse);
    }
}