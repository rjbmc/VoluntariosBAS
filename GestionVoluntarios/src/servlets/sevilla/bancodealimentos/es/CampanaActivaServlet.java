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
import util.sevilla.bancodealimentos.es.LogUtil;

/**
 * Servlet que busca la campaña activa (estado = 'S') y devuelve
 * sus datos en formato JSON.
 */
@WebServlet("/campana-activa")
public class CampanaActivaServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static final Logger logger = LoggerFactory.getLogger(CampanaActivaServlet.class);
    private final ObjectMapper mapper = new ObjectMapper();

    public static class CampanaDTO {
        public String campana;
        public String denominacion;
        public String fecha1;
        public String fecha2;
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
                
                Date f1 = rs.getDate("fecha1");
                Date f2 = rs.getDate("fecha2");
                dto.fecha1 = (f1 != null) ? f1.toString() : null;
                dto.fecha2 = (f2 != null) ? f2.toString() : null;
                
                mapper.writeValue(response.getWriter(), dto);

            } else {
                logger.debug("Solicitud de campaña activa: No se encontró ninguna activa en este momento.");
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "No se encontró ninguna campaña activa.");
            }

        } catch (SQLException e) {
            // ¡NUEVO! Usamos el sistema de logging centralizado
            LogUtil.logException(logger, e, "Error de SQL al consultar la campaña activa");
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error al consultar la base de datos. El error ha sido registrado.");
        }
    }
}
