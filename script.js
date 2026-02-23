(() => {
    const INSTAGRAM_URL = 'https://www.instagram.com/purodropshipping?igsh=MWFhZzd1czQ5YzBncQ==';
    const modalTriggers = document.querySelectorAll('[data-modal-target]');
    const modals = document.querySelectorAll('.modal');
    const instagramButtons = document.querySelectorAll('.btn-instagram');

    let activeModal = null;
    let lastFocusedElement = null;

    function getFocusableElements(container) {
        return container.querySelectorAll(
            'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])'
        );
    }

    function openModal(modalId) {
        const modal = document.getElementById(modalId);
        if (!modal) return;

        lastFocusedElement = document.activeElement instanceof HTMLElement ? document.activeElement : null;
        modal.hidden = false;
        document.body.classList.add('no-scroll');
        activeModal = modal;

        const focusableElements = getFocusableElements(modal);
        if (focusableElements.length > 0 && focusableElements[0] instanceof HTMLElement) {
            focusableElements[0].focus();
        }
    }

    function closeModal(modalId) {
        const modal = document.getElementById(modalId);
        if (!modal) return;

        modal.hidden = true;
        if (activeModal && activeModal.id === modalId) {
            activeModal = null;
            document.body.classList.remove('no-scroll');
        }

        if (lastFocusedElement instanceof HTMLElement) {
            lastFocusedElement.focus();
            lastFocusedElement = null;
        }
    }

    function openInstagram() {
        window.open(INSTAGRAM_URL, '_blank', 'noopener,noreferrer');
    }

    modalTriggers.forEach((trigger) => {
        trigger.addEventListener('click', () => {
            const modalId = trigger.getAttribute('data-modal-target');
            if (modalId) openModal(modalId);
        });
    });

    modals.forEach((modal) => {
        const closeButton = modal.querySelector('[data-close-modal]');

        if (closeButton instanceof HTMLElement) {
            closeButton.addEventListener('click', () => closeModal(modal.id));
        }

        modal.addEventListener('click', (event) => {
            if (event.target === modal) {
                closeModal(modal.id);
            }
        });
    });

    instagramButtons.forEach((button) => {
        button.addEventListener('click', openInstagram);
    });

    document.addEventListener('keydown', (event) => {
        if (!activeModal) return;

        if (event.key === 'Escape') {
            closeModal(activeModal.id);
            return;
        }

        if (event.key !== 'Tab') return;

        const focusableElements = Array.from(getFocusableElements(activeModal)).filter(
            (element) => !element.hasAttribute('disabled')
        );

        if (focusableElements.length === 0) {
            event.preventDefault();
            return;
        }

        const first = focusableElements[0];
        const last = focusableElements[focusableElements.length - 1];

        if (event.shiftKey && document.activeElement === first) {
            event.preventDefault();
            last.focus();
        } else if (!event.shiftKey && document.activeElement === last) {
            event.preventDefault();
            first.focus();
        }
    });
})();
