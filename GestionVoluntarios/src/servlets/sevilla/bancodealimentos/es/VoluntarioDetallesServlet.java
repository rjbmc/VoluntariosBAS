package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;

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

@WebServlet("/voluntario-detalles")
public class VoluntarioDetallesServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static final Logger logger = LoggerFactory.getLogger(VoluntarioDetallesServlet.class);
    private final ObjectMapper mapper = new ObjectMapper();

    public static class VoluntarioDTO {
        public String nombre;
        public String apellidos;
        public String dni;
        public String email;
        public String telefono;
        public String fechaNacimiento;
        public String cp;
        public Integer tiendaReferencia;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("usuario") == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "No hay una sesión activa.");
            return;
        }

        // --- LÓGICA DE BÚSQUEDA MEJORADA ---
        String usuarioEnSesion = (String) session.getAttribute("usuario");
        boolean isAdmin = false;
        Object adminAttr = session.getAttribute("isAdmin");
        if (adminAttr instanceof Boolean) isAdmin = (Boolean) adminAttr;
        else if ("S".equals(adminAttr)) isAdmin = true;

        // Si se pasa un parámetro 'usuario' y quien consulta es ADMIN, buscamos ese usuario.
        // Si no, buscamos los datos del usuario de la sesión actual.
        String usuarioABuscar = request.getParameter("usuario");
        if (usuarioABuscar == null || !isAdmin) {
            usuarioABuscar = usuarioEnSesion;
        }

        String sql = "SELECT Nombre, Apellidos, `DNI NIF`, Email, telefono, fechaNacimiento, cp, tiendaReferencia FROM voluntarios WHERE Usuario = ?";

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, usuarioABuscar);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    VoluntarioDTO dto = new VoluntarioDTO();
                    dto.nombre = rs.getString("Nombre");
                    dto.apellidos = rs.getString("Apellidos");
                    dto.dni = rs.getString("DNI NIF");
                    dto.email = rs.getString("Email");
                    dto.telefono = rs.getString("telefono");
                    
                    java.sql.Date fechaNac = rs.getDate("fechaNacimiento");
                    if (fechaNac != null) {
                        dto.fechaNacimiento = new SimpleDateFormat("yyyy-MM-dd").format(fechaNac);
                    } else {
                        dto.fechaNacimiento = "";
                    }
                    
                    dto.cp = rs.getString("cp");
                    int tiendaId = rs.getInt("tiendaReferencia");
                    dto.tiendaReferencia = rs.wasNull() ? null : tiendaId;

                    mapper.writeValue(response.getWriter(), dto);
                } else {
                    response.sendError(HttpServletResponse.SC_NOT_FOUND, "Usuario no encontrado.");
                } 
            }
        } catch (SQLException e) {
            logger.error("Error de base de datos en VoluntarioDetalles", e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }
}