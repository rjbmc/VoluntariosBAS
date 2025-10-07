// registro-voluntario.js

// Utilidades para validación
function showError(inputId, message) {
    const input = document.getElementById(inputId);
    const errorDiv = document.getElementById('error-' + inputId);
    if (input) input.classList.add('border-red-500');
    if (errorDiv) {
        errorDiv.textContent = message;
        errorDiv.classList.remove('hidden');
    }
}
function clearError(inputId) {
    const input = document.getElementById(inputId);
    const errorDiv = document.getElementById('error-' + inputId);
    if (input) input.classList.remove('border-red-500');
    if (errorDiv) errorDiv.classList.add('hidden');
}
function validateEmail(email) {
    return /^[\w-.]+@([\w-]+\.)+[\w-]{2,4}$/.test(email);
}
function validateTelefono(tel) {
    return /^\d{9,11}$/.test(tel);
}
function validateDNI(dni) {
    return /^\d{8}[A-Za-z]$/.test(dni);
}
function validateCP(cp) {
    return /^\d{5}$/.test(cp);
}
function validatePassword(pass) {
    return pass.length >= 8;
}

// Mostrar/Ocultar contraseña
const claveInput = document.getElementById('clave');
const toggleClaveBtn = document.getElementById('toggleClave');
if (toggleClaveBtn && claveInput) {
    toggleClaveBtn.addEventListener('click', function() {
        claveInput.type = claveInput.type === 'password' ? 'text' : 'password';
        toggleClaveBtn.setAttribute('aria-label', claveInput.type === 'password' ? 'Mostrar contraseña' : 'Ocultar contraseña');
    });
}

// Consentimiento: activar solo si se han abierto ambos documentos
let openedCompromisos = false;
let openedPrivacidad = false;
document.getElementById('link-compromisos').addEventListener('click', function() {
    openedCompromisos = true;
    setTimeout(checkConsentReady, 2000); // Simula tiempo de lectura
});
document.getElementById('link-privacidad').addEventListener('click', function() {
    openedPrivacidad = true;
    setTimeout(checkConsentReady, 2000);
});
function checkConsentReady() {
    if (openedCompromisos && openedPrivacidad) {
        document.getElementById('consent-checkbox').disabled = false;
    }
}
document.getElementById('consent-checkbox').addEventListener('change', function(e) {
    document.getElementById('submit-btn').disabled = !e.target.checked;
});

// Validación y envío del formulario
const form = document.getElementById('registroForm');
form.addEventListener('submit', async function(e) {
    e.preventDefault();
    let valid = true;
    // Limpiar errores
    ['nombre','apellidos','dni','fechaNacimiento','email','telefono','cp','usuario','clave','confirmar_clave'].forEach(clearError);
    // Validaciones
    const nombre = form.nombre.value.trim();
    const apellidos = form.apellidos.value.trim();
    const dni = form.dni.value.trim();
    const fechaNacimiento = form.fechaNacimiento.value;
    const email = form.email.value.trim();
    const telefono = form.telefono.value.trim();
    const cp = form.cp.value.trim();
    const usuario = form.usuario.value.trim();
    const clave = form.clave.value;
    const confirmar_clave = form.confirmar_clave.value;
    if (!nombre) { showError('nombre', 'El nombre es obligatorio.'); valid = false; }
    if (!apellidos) { showError('apellidos', 'Los apellidos son obligatorios.'); valid = false; }
    if (!validateDNI(dni)) { showError('dni', 'DNI/NIF no válido.'); valid = false; }
    if (!fechaNacimiento) { showError('fechaNacimiento', 'La fecha de nacimiento es obligatoria.'); valid = false; }
    if (!validateEmail(email)) { showError('email', 'Correo electrónico no válido.'); valid = false; }
    if (!validateTelefono(telefono)) { showError('telefono', 'Teléfono no válido.'); valid = false; }
    if (!validateCP(cp)) { showError('cp', 'Código postal no válido.'); valid = false; }
    if (!usuario) { showError('usuario', 'El usuario es obligatorio.'); valid = false; }
    if (!validatePassword(clave)) { showError('clave', 'La contraseña debe tener al menos 8 caracteres.'); valid = false; }
    if (clave !== confirmar_clave) { showError('confirmar_clave', 'Las contraseñas no coinciden.'); valid = false; }
    if (!document.getElementById('consent-checkbox').checked) {
        showMessage('Debes aceptar los compromisos y la política de datos.', 'bg-red-600');
        valid = false;
    }
    if (!valid) return;
    // Spinner y deshabilitar botón
    const submitBtn = document.getElementById('submit-btn');
    const spinner = document.getElementById('spinner');
    submitBtn.disabled = true;
    spinner.classList.remove('hidden');
    // Simulación de envío (AJAX)
    try {
        // Aquí iría la llamada real al backend
        await new Promise(res => setTimeout(res, 1500));
        showMessage('¡Registro completado! Redirigiendo...', 'bg-green-600');
        setTimeout(() => { window.location.href = 'login.html'; }, 2500);
    } catch (err) {
        showMessage('Error en el registro. Inténtalo de nuevo.', 'bg-red-600');
        submitBtn.disabled = false;
    } finally {
        spinner.classList.add('hidden');
    }
});

function showMessage(msg, colorClass) {
    const messageDiv = document.getElementById('message');
    messageDiv.textContent = msg;
    messageDiv.className = `p-4 mt-4 text-sm text-center text-white rounded-md ${colorClass}`;
    messageDiv.classList.remove('hidden');
}
