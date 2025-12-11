package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import util.sevilla.bancodealimentos.es.DatabaseUtil;

/**
 * Servlet que busca la campaña activa (estado = 'S') y devuelve
 * sus datos en formato JSON.
 */
@WebServlet("/campana-activa")
public class CampanaActivaServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    
    // 1. Logger SLF4J
    private static final Logger logger = LoggerFactory.getLogger(CampanaActivaServlet.class);
    
    // 2. Jackson ObjectMapper
    private final ObjectMapper mapper = new ObjectMapper();

    // DTO para enviar los datos de la campaña al frontend
    public static class CampanaDTO {
        public String campana;      // ID (UUID)
        public String denominacion;
        public String fecha1;       // Fecha inicio (yyyy-MM-dd)
        public String fecha2;       // Fecha fin (yyyy-MM-dd)
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        String sql = "SELECT Campana, denominacion, fecha1, fecha2 FROM campanas WHERE estado = 'S' LIMIT 1";
        
        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            if (rs.next()) {
                CampanaDTO dto = new CampanaDTO();
                dto.campana = rs.getString("Campana");
                dto.denominacion = rs.getString("denominacion");
                
                // Convertimos java.sql.Date a String (formato por defecto yyyy-MM-dd)
                Date f1 = rs.getDate("fecha1");
                Date f2 = rs.getDate("fecha2");
                dto.fecha1 = (f1 != null) ? f1.toString() : null;
                dto.fecha2 = (f2 != null) ? f2.toString() : null;
                
                // 3. Serialización automática con Jackson
                mapper.writeValue(response.getWriter(), dto);

            } else {
                // Es normal que a veces no haya campaña activa, usamos WARN o INFO según preferencia
                logger.debug("Solicitud de campaña activa: No se encontró ninguna activa en este momento.");
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "No se encontró ninguna campaña activa.");
            }

        } catch (SQLException e) {
            logger.error("Error SQL al consultar la campaña activa.", e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error al consultar la base de datos.");
        }
    }
}