package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

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

/**
 * Servlet que devuelve las listas de supervisores y zonas para los filtros
 * de la pantalla de gestión de tiendas.
 */
@WebServlet("/admin-filtros-tiendas")
public class AdminFiltrosTiendasServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    
    // 1. Logger SLF4J
    private static final Logger logger = LoggerFactory.getLogger(AdminFiltrosTiendasServlet.class);
    
    // 2. Jackson ObjectMapper
    private final ObjectMapper mapper = new ObjectMapper();

    // DTO para estructurar la respuesta con dos listas
    public static class FiltrosDTO {
        public List<String> supervisores = new ArrayList<>();
        public List<String> zonas = new ArrayList<>();
    }

    // 3. Verificación de seguridad estandarizada
    private boolean isAdmin(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("usuario") == null) return false;
        
        Object isAdminAttr = session.getAttribute("isAdmin");
        return (isAdminAttr instanceof Boolean && (Boolean) isAdminAttr) || 
               ("S".equals(isAdminAttr));
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        if (!isAdmin(request)) {
            logger.warn("Acceso denegado a AdminFiltrosTiendas. IP: {}", request.getRemoteAddr());
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Acceso denegado.");
            return;
        }

        FiltrosDTO filtros = new FiltrosDTO();

        try (Connection conn = DatabaseUtil.getConnection()) {
            
            // 1. Obtener lista de supervisores distintos
            String sqlSupervisores = "SELECT DISTINCT Supervisor FROM tiendas WHERE Supervisor IS NOT NULL AND Supervisor != '' ORDER BY Supervisor";
            try (PreparedStatement stmt = conn.prepareStatement(sqlSupervisores);
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    filtros.supervisores.add(rs.getString("Supervisor"));
                }
            }

            // 2. Obtener lista de zonas distintas
            String sqlZonas = "SELECT DISTINCT Zona FROM tiendas WHERE Zona IS NOT NULL AND Zona != '' ORDER BY Zona";
            try (PreparedStatement stmt = conn.prepareStatement(sqlZonas);
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    filtros.zonas.add(rs.getString("Zona"));
                }
            }
            
            // 3. Serialización automática con Jackson (mucho más limpio que StringBuilder)
            mapper.writeValue(response.getWriter(), filtros);

        } catch (SQLException e) {
            logger.error("Error SQL al consultar los filtros de tiendas.", e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error al consultar los filtros.");
        }
    }
}